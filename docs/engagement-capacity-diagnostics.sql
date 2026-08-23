-- Dynamic engagement capacity diagnostic query.
--
-- Purpose: operational/product analysis of current or recent reliability-derived
-- admission capacity, active lock counts, headroom and natural overshoot.
-- This is not runtime application logic.
--
-- Limitation: user_reliability_events is temporally bounded and expired events
-- are deleted. This query can reconstruct current/recent score and capacity
-- from events still present in the operational database, but it cannot provide
-- indefinite historical per-user score or capacity trajectories after those
-- rows are gone. Use Micrometer/time-series retention for initial longitudinal
-- aggregate visibility; true long-term cohort or causal analysis requires a
-- separate analytics/history pipeline.

with params as (
    select
        100.0::double precision as reliability_base_score,
        10::integer as full_weight_days,
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
active_event_scores as (
    select
        e.user_id,
        sum(
            e.delta *
            case
                when e.occurred_at > now() then 1.0
                when e.occurred_at >= now() - (p.full_weight_days || ' days')::interval then 1.0
                when e.occurred_at < now() - (p.expiration_days || ' days')::interval then 0.0
                else 0.5
            end
        ) as weighted_delta
    from user_reliability_events e
    cross join params p
    where e.expires_at > now()
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
        p.reliability_base_score + coalesce(s.weighted_delta, 0.0) as effective_score,
        coalesce(l.active_match_locks, 0) as active_match_locks,
        coalesce(l.active_connection_locks, 0) as active_connection_locks,
        p.*
    from users u
    cross join params p
    left join active_event_scores s on s.user_id = u.id
    left join active_lock_counts l on l.user_id = u.id
),
derived_capacity as (
    select
        c.user_id,
        c.effective_score,
        round(
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
        )::integer as raw_match_cap,
        round(
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
        )::integer as raw_connection_cap,
        c.active_match_locks,
        c.active_connection_locks,
        c.match_min,
        c.match_max,
        c.connection_min,
        c.connection_max
    from current_scores c
)
select
    user_id,
    effective_score,
    least(greatest(raw_match_cap, match_min), match_max) as effective_match_cap,
    least(greatest(raw_connection_cap, connection_min), connection_max) as effective_connection_cap,
    active_match_locks,
    active_connection_locks,
    least(greatest(raw_match_cap, match_min), match_max) - active_match_locks as match_headroom,
    least(greatest(raw_connection_cap, connection_min), connection_max) - active_connection_locks as connection_headroom,
    greatest(active_match_locks - least(greatest(raw_match_cap, match_min), match_max), 0) as match_overshoot,
    greatest(active_connection_locks - least(greatest(raw_connection_cap, connection_min), connection_max), 0) as connection_overshoot
from derived_capacity
where active_match_locks > 0
   or active_connection_locks > 0
   or effective_score <> (select reliability_base_score from params)
order by connection_overshoot desc, match_overshoot desc, effective_score asc;
