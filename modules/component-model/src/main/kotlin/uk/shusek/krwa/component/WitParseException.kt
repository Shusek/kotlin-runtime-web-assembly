package uk.shusek.krwa.component

class WitParseException : ComponentModelException {
    constructor(message: String?) : super(message)

    constructor(message: String?, cause: Throwable?) : super(message, cause)
}
