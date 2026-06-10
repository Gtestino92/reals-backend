package com.reals.backend.validation

object PlainText {
    const val REGEX: String = "^[^\\p{Cntrl}<>]*$"
    const val MESSAGE: String = "must be plain text and cannot contain markup characters"

    fun requireValid(
        fieldName: String,
        value: String
    ) {
        require(value.none { it.isISOControl() || it == '<' || it == '>' }) {
            "$fieldName $MESSAGE"
        }
    }
}
