package uk.shusek.krwa.component

public actual typealias ComponentModelJvmClass<T> = java.lang.Class<T>

public actual inline fun <reified T : Any> componentModelJvmClass(): ComponentModelJvmClass<T> =
    T::class.java
