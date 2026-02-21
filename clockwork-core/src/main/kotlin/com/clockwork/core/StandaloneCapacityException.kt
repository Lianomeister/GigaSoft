package com.clockwork.core

class StandaloneCapacityException(
    val code: String,
    message: String
) : IllegalStateException(message)
