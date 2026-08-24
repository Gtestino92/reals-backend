-- Dynamic engagement capacity diagnostic query.
--
-- Purpose: operational/product analysis of current or recent reliability-derived
-- admission capacity, active lock counts, headroom and natural overshoot.
-- This is not runtime application logic.
--
-- Runtime configuration warning:
-- The concrete parameter defaults below mirror the neutral backend defaults.
-- Update them to match the runtime/environment being analyzed before using this
-- query. For example, local-firebase intentionally uses high static capacities
-- such as 100/100 with reliability-capacity min/max aligned to those overrides;
-- 5/4 is not a universal operational assumption.
--
-- Scoring boundary semantics:
-- Runtime reliability weighting uses whole-day age semantics equivalent to
-- ChronoUnit.DAYS.between(occurredAt, now): age_days is the number of complete
-- 24-hour days between occurred_at and the query clock. Events with
-- age_days < full_weight_days have weight 1.0; events with
-- age_days < full_weight_days + half_weight_days have weight 0.5; later events
-- contribute 0.0. Retained rows past the scoring window may still exist until
-- expires_at, but they do not affect capacity.
--
-- Limitation:
-- user_reliability_events is temporally bounded and expired events are deleted.
-- This query can reconstruct current/recent score and capacity from events
-- still present in the operational database, but it cannot provide indefinite
-- historical per-user score or capacity trajectories after those rows are gone.
-- The backend emits aggregate Micrometer metrics, but durable longitudinal
-- retention across process restarts/deployments requires an external metrics
-- backend or dedicated analytics/history pipeline.

with params as (
    select
        true::boolean as reliability_enabled,
        100.0::double precision as reliability_base_score,
        10::integer as full_weight_days,
        10::integer as half_weight_days,
        20::integer as expiration_days,
        5::integer as match_base,
        3::integer as match_min,
        9::integer as match_max,
        20.0::double precision as match_reward_scale,
        10.0::double precision as match_penalty_scale,
        4::integer as connection_base,
        2::integer as connection_min,
        6::integer as connection_max,
        30.0::double precision as connection_reward_scale,
        10.0::double precision as connection_penalty_scale
),
active_event_ages as (
    select
        e.user_id,
        e.delta,
        floor(extract(epoch from (now() - e.occurred_at)) / 86400)::integer as age_days
    from user_reliability_events e
    where e.expires_at > now()
),
active_event_scores as (
    select
        e.user_id,
        sum(
            e.delta *
            case
                when e.age_days < p.full_weight_days then 1.0
                when e.age_days < p.full_weight_days + p.half_weight_days then 0.5
                else 0.0
            end
        ) as weighted_delta
    from active_event_ages e
    cross join params p
    group by e.user_id
),
active_lock_counts as (
    select
        l.user_id,
        count(*) filter (where l.engagement_type = 'MATCH')::integer as active_match_locks,
        count(*) filter (where l.engagement_type = 'CONNECTION')::integer as active_connection_locks
    from active_engagement_locks l
    group by l.user_id
),
current_scores as (
    select
        u.id as user_id,
        case
            when p.reliability_enabled then p.reliability_base_score + coalesce(s.weighted_delta, 0.0)
            else p.reliability_base_score
        end as effective_score,
        coalesce(l.active_match_locks, 0) as active_match_locks,
        coalesce(l.active_connection_locks, 0) as active_connection_locks,
        p.*
    from users u
    cross join params p
    left join active_event_scores s on s.user_id = u.id
    left join active_lock_counts l on l.user_id = u.id
    where u.status = 'ACTIVE'
    -- Remove the ACTIVE-user filter only deliberately, for anti-abuse/history
    -- inspection where deleted users should be included.
),
derived_capacity as (
    select
        c.user_id,
        c.reliability_enabled,
        c.reliability_base_score,
        c.effective_score,
        case
            when not c.reliability_enabled then c.match_base
            else round(
                case
                    when c.effective_score >= c.reliability_base_score then
                        c.match_base +
                        (c.match_max - c.match_base) *
                        (
                            power(c.effective_score - c.reliability_base_score, 2) /
                            (
                                power(c.effective_score - c.reliability_base_score, 2) +
                                power(c.match_reward_scale, 2)
                            )
                        )
                    else
                        c.match_base -
                        (c.match_base - c.match_min) *
                        (
                            power(abs(c.effective_score - c.reliability_base_score), 2) /
                            (
                                power(abs(c.effective_score - c.reliability_base_score), 2) +
                                power(c.match_penalty_scale, 2)
                            )
                        )
                end
            )::integer
        end as raw_match_cap,
        case
            when not c.reliability_enabled then c.connection_base
            else round(
                case
                    when c.effective_score >= c.reliability_base_score then
                        c.connection_base +
                        (c.connection_max - c.connection_base) *
                        (
                            power(c.effective_score - c.reliability_base_score, 2) /
                            (
                                power(c.effective_score - c.reliability_base_score, 2) +
                                power(c.connection_reward_scale, 2)
                            )
                        )
                    else
                        c.connection_base -
                        (c.connection_base - c.connection_min) *
                        (
                            power(abs(c.effective_score - c.reliability_base_score), 2) /
                            (
                                power(abs(c.effective_score - c.reliability_base_score), 2) +
                                power(c.connection_penalty_scale, 2)
                            )
                        )
                end
            )::integer
        end as raw_connection_cap,
        c.active_match_locks,
        c.active_connection_locks,
        c.match_min,
        c.match_max,
        c.connection_min,
        c.connection_max
    from current_scores c
),
capacity_rows as (
    select
        user_id,
        effective_score,
        case
            when effective_score < reliability_base_score then 'below_base'
            when effective_score > reliability_base_score then 'above_base'
            else 'neutral'
        end as reliability_direction,
        least(greatest(raw_match_cap, match_min), match_max) as effective_match_cap,
        least(greatest(raw_connection_cap, connection_min), connection_max) as effective_connection_cap,
        active_match_locks,
        active_connection_locks,
        least(greatest(raw_match_cap, match_min), match_max) - active_match_locks as match_headroom,
        least(greatest(raw_connection_cap, connection_min), connection_max) - active_connection_locks as connection_headroom,
        greatest(active_match_locks - least(greatest(raw_match_cap, match_min), match_max), 0) as match_overshoot,
        greatest(active_connection_locks - least(greatest(raw_connection_cap, connection_min), connection_max), 0) as connection_overshoot
    from derived_capacity
)
select
    user_id,
    effective_score,
    reliability_direction,
    effective_match_cap,
    effective_connection_cap,
    active_match_locks,
    active_connection_locks,
    match_headroom,
    connection_headroom,
    match_overshoot,
    connection_overshoot
from capacity_rows
where active_match_locks > 0
   or active_connection_locks > 0
   or effective_score <> (select reliability_base_score from params)
order by connection_overshoot desc, match_overshoot desc, effective_score asc;

-- Optional population summary:
-- Replace the final SELECT above with this aggregate to inspect cap/direction
-- distributions and overshoot prevalence across active users.
--
-- select
--     reliability_direction,
--     effective_match_cap,
--     effective_connection_cap,
--     count(*) as user_count,
--     avg(active_match_locks)::numeric(10, 2) as avg_active_match_locks,
--     avg(active_connection_locks)::numeric(10, 2) as avg_active_connection_locks,
--     count(*) filter (where match_overshoot > 0 or connection_overshoot > 0) as users_in_overshoot
-- from capacity_rows
-- group by reliability_direction, effective_match_cap, effective_connection_cap
-- order by reliability_direction, effective_match_cap, effective_connection_cap;
