package uk.shusek.krwa.component

import java.lang.reflect.AccessibleObject
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Member
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
    private const val DEFAULT_CONSTRUCTOR_MARKER = "kotlin.jvm.internal.DefaultConstructorMarker"

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
     * inline-class parameters, which is recognised by its public synthetic twin taking a trailing
     * `DefaultConstructorMarker`. Constructors declared private stay unreachable.
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
                    hasPublicSyntheticTwin(constructor, declared)
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
                    hasPublicSyntheticTwin(member, member.declaringClass.declaredConstructors))
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

    private fun hasPublicSyntheticTwin(
        constructor: Constructor<*>,
        declared: Array<Constructor<*>>,
    ): Boolean {
        val parameters = constructor.parameterTypes
        for (candidate in declared) {
            if (!candidate.isSynthetic || !Modifier.isPublic(candidate.modifiers)) {
                continue
            }
            val candidateParameters = candidate.parameterTypes
            if (
                candidateParameters.size != parameters.size + 1 ||
                    candidateParameters[parameters.size].name != DEFAULT_CONSTRUCTOR_MARKER
            ) {
                continue
            }
            var matches = true
            for (index in parameters.indices) {
                if (candidateParameters[index] != parameters[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return true
            }
        }
        return false
    }
}
