package uk.shusek.krwa.component

/**
 * Returns whether a guest may store [target] as the contents of a symbolic link it creates with
 * `descriptor.symlink-at`.
 *
 * Only relative targets that never go up are accepted: no leading `/` or `\`, no drive prefix such
 * as `C:`, and no `..` component when the target is split on both `/` and `\`. Such a link resolves
 * to the directory that holds it or to something below it, so it stays inside the preopen wherever
 * the guest later moves the link or one of its parent directories, and a chain of such links never
 * climbs either.
 *
 * Checking the target lexically against the link's current location is not enough. A guest can
 * create `a/b/link -> ../../x`, which points inside the preopen, and then rename `a/b` to `b`,
 * after which the same link points next to the preopen. A `..` that follows a link component, as
 * in `self/../x` with `self -> .`, climbs one level further than the lexical path suggests. Host
 * code that follows links in a guest-writable directory, such as `java.io.File.deleteRecursively`,
 * would then read, write or delete host files outside the sandbox.
 */
internal fun isGuestSymlinkTargetConfined(target: String): Boolean {
    if (target.startsWith('/') || target.startsWith('\\')) {
        return false
    }
    if (target.length >= 2 && target[1] == ':' && target[0].isLetter()) {
        return false
    }
    return target.split('/', '\\').none { component -> component == ".." }
}
