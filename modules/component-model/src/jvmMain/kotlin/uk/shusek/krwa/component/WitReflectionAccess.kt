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
    /** Public, non-static methods of [type], excluding those declared by `java.lang.Object`. */
    fun publicInstanceMethods(type: Class<*>): List<Method> =
        type.methods.filter { method -> isEligible(method) }

    fun publicZeroArgMethod(type: Class<*>, name: String): Method? {
        for (method in publicInstanceMethods(type)) {
            if (method.parameterCount == 0 && method.name == name) {
                return method
            }
        }
        return null
    }

    /** The public, non-static field [name] of [type] or one of its supertypes. */
    fun publicField(type: Class<*>, name: String): Field? =
        try {
            type.getField(name).takeIf { field -> isEligible(field) }
        } catch (_: NoSuchFieldException) {
            null
        }

    fun publicConstructors(type: Class<*>): List<Constructor<*>> =
        type.constructors.filter { constructor -> !constructor.isSynthetic }

    /**
     * Makes a public [member] invokable when only its declaring class is inaccessible. The member
     * itself must already be public; this never exposes private, protected or package-private
     * members.
     */
    fun <T> open(member: T): T where T : AccessibleObject, T : Member {
        require(Modifier.isPublic(member.modifiers)) {
            "only public members may be reached through WIT names: ${member.declaringClass.name}.${member.name}"
        }
        if (!Modifier.isPublic(member.declaringClass.modifiers)) {
            member.trySetAccessible()
        }
        return member
    }

    private fun isEligible(member: Member): Boolean =
        Modifier.isPublic(member.modifiers) &&
            !Modifier.isStatic(member.modifiers) &&
            member.declaringClass != Any::class.java
}
