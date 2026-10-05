package uk.shusek.krwa.component

import java.lang.invoke.MethodType
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.metadata.Visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.signature
import kotlin.metadata.visibility

/**
 * Reflection boundary for WIT-driven access to host objects.
 *
 * WIT names can come from a plugin component, so a guest effectively chooses which host members
 * are looked up by name. Only public instance members declared outside `java.lang.Object` are
 * eligible, so private state and `Object` methods are never reachable, and accessibility is only
 * widened for public members whose declaring class is itself not public (an anonymous or private
 * contract implementation). Non-public members are never opened.
 */
internal object WitReflectionAccess {
    private val publicKotlinConstructors =
        object : ClassValue<Set<String>>() {
            override fun computeValue(type: Class<*>): Set<String> {
                val annotation = type.getAnnotation(Metadata::class.java) ?: return emptySet()
                val metadata =
                    try {
                        KotlinClassMetadata.readStrict(annotation) as? KotlinClassMetadata.Class
                    } catch (_: IllegalArgumentException) {
                        null
                    } ?: return emptySet()
                return metadata.kmClass.constructors
                    .filter { it.visibility == Visibility.PUBLIC }
                    .mapNotNull { it.signature?.descriptor }
                    .toSet()
            }
        }

    /** Public, non-static methods of [type], excluding those declared by `java.lang.Object`. */
    fun publicInstanceMethods(type: Class<*>): List<Method> =
        type.methods.filter { method -> isEligible(method) }

    /**
     * The public zero-argument method [name] of [type]. Kotlin compiles members whose signature
     * involves an inline class (`ULong`, `UInt`, value classes) under the mangled name `name-<hash>`;
     * `-` cannot appear in a Java identifier, so such a method is accepted when no exact match exists.
     */
    fun publicZeroArgMethod(type: Class<*>, name: String): Method? {
        var mangled: Method? = null
        for (method in publicInstanceMethods(type)) {
            if (method.parameterCount != 0) {
                continue
            }
            if (method.name == name) {
                return method
            }
            if (mangled == null && isMangledName(method.name, name)) {
                mangled = method
            }
        }
        return mangled
    }

    /** The public, non-static field [name] of [type] or one of its supertypes. */
    fun publicField(type: Class<*>, name: String): Field? =
        try {
            type.getField(name).takeIf { field -> isEligible(field) }
        } catch (_: NoSuchFieldException) {
            null
        }

    /**
     * Constructors of [type] that are public in the source language. Besides JVM-public
     * constructors this includes the private constructor Kotlin emits for a public constructor with
     * inline-class parameters. Kotlin metadata must confirm the visibility of that exact JVM
     * signature: a public synthetic twin can also be emitted for a source-private constructor.
     */
    fun publicConstructors(type: Class<*>): List<Constructor<*>> {
        val declared = type.declaredConstructors
        val result = ArrayList<Constructor<*>>()
        for (constructor in declared) {
            if (constructor.isSynthetic) {
                continue
            }
            if (
                Modifier.isPublic(constructor.modifiers) ||
                    isPublicKotlinConstructor(constructor)
            ) {
                result.add(constructor)
            }
        }
        return result
    }

    /**
     * Makes a source-public [member] invokable when the JVM would otherwise refuse: its declaring
     * class is not public, or it is a Kotlin inline-class constructor compiled as private. This
     * never exposes members declared private, protected or package-private.
     */
    fun <T> open(member: T): T where T : AccessibleObject, T : Member {
        val sourcePublic =
            Modifier.isPublic(member.modifiers) ||
                (member is Constructor<*> &&
                    isPublicKotlinConstructor(member))
        require(sourcePublic) {
            "only public members may be reached through WIT names: ${member.declaringClass.name}.${member.name}"
        }
        if (
            !Modifier.isPublic(member.modifiers) ||
                !Modifier.isPublic(member.declaringClass.modifiers)
        ) {
            member.trySetAccessible()
        }
        return member
    }

    private fun isEligible(member: Member): Boolean =
        Modifier.isPublic(member.modifiers) &&
            !Modifier.isStatic(member.modifiers) &&
            member.declaringClass != Any::class.java

    private fun isMangledName(actual: String, name: String): Boolean =
        actual.length > name.length + 1 && actual.startsWith(name) && actual[name.length] == '-'

    private fun isPublicKotlinConstructor(constructor: Constructor<*>): Boolean {
        val publicSignatures = publicKotlinConstructors.get(constructor.declaringClass)
        val descriptor =
            MethodType.methodType(Void.TYPE, constructor.parameterTypes.toList())
                .toMethodDescriptorString()
        // For a source-public inline-class constructor, metadata names the marker bridge rather
        // than the private implementation. Source-private factories are marked private in metadata.
        val markerDescriptor =
            descriptor.removeSuffix(")V") + "Lkotlin/jvm/internal/DefaultConstructorMarker;)V"
        return descriptor in publicSignatures || markerDescriptor in publicSignatures
    }
}
