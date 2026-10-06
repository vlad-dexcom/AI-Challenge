package com.example.core.platform

import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/** True when [e] means "the host could not be resolved" (no network), whatever the engine reports. */
fun isUnresolvedHost(e: Throwable): Boolean = e is UnresolvedAddressException || e is UnknownHostException
