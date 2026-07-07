package com.reals.backend.validation

object PlainText {
    const val REGEX: String =
        """^[^\u0000-\u0009\u000B\u000C\u000E-\u001F\u007F-\u009F<>]*$"""

    const val MESSAGE: String = "must be plain text and cannot contain markup characters"

    fun requireValid(
        fieldName: String,
        value: String
    ) {
        require(value.none { (it.isISOControl() && it != '\n' && it != '\r') || it == '<' || it == '>' }) {
            "$fieldName $MESSAGE"
        }
    }
}
