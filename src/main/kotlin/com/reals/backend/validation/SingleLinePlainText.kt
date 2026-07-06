package com.reals.backend.validation

object SingleLinePlainText {
    const val REGEX: String =
        """^[^\u0000-\u001F\u007F-\u009F\u2028\u2029<>]*$"""

    const val MESSAGE: String =
        "must be single-line plain text and cannot contain markup characters"
}