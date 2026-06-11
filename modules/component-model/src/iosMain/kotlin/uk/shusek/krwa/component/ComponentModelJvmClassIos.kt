package uk.shusek.krwa.component

public actual class ComponentModelJvmClass<T : Any>

public actual inline fun <reified T : Any> componentModelJvmClass(): ComponentModelJvmClass<T> =
    throw ComponentModelException("JVM class references are not available on iOS")
