package uk.shusek.krwa.component

class WitPackage
private constructor(private val packageName: String?, declarations: List<Declaration>) {
    private val declarations: List<Declaration> = immutableList(declarations)

    fun packageName(): String? = packageName

    fun declarations(): List<Declaration> = declarations

    fun interfaces(): List<InterfaceDeclaration> {
        val result = ArrayList<InterfaceDeclaration>()
        for (declaration in declarations) {
            if (declaration is InterfaceDeclaration) {
                result.add(declaration)
            }
        }
        return result
    }

    fun worlds(): List<WorldDeclaration> {
        val result = ArrayList<WorldDeclaration>()
        for (declaration in declarations) {
            if (declaration is WorldDeclaration) {
                result.add(declaration)
            }
        }
        return result
    }

    interface Declaration {
        fun name(): String

        fun packageName(): String? = null

        fun qualifiedName(): String = WitNames.qualifiedInterfaceName(packageName(), name())
    }

    interface InterfaceMember : Declaration

    class UseDeclaration(path: String, items: List<UseItem>) : Declaration, InterfaceMember {
        private val path: String = requireName(path)
        private val items: List<UseItem> = immutableList(items)

        override fun name(): String = path

        fun path(): String = path

        fun items(): List<UseItem> = items
    }

    class UseItem(name: String, alias: String?) {
        private val name: String = requireName(name)
        private val alias: String? = alias?.let(::requireName)

        fun name(): String = name

        fun alias(): String? = alias

        fun localName(): String = alias ?: name
    }

    class InterfaceDeclaration(packageName: String?, name: String, members: List<InterfaceMember>) :
        Declaration {
        private val packageName: String? = packageName?.let(::requireName)
        private val name: String = requireName(name)
        private val members: List<InterfaceMember> = immutableList(members)

        override fun name(): String = name

        override fun packageName(): String? = packageName

        fun members(): List<InterfaceMember> = members

        fun functions(): List<Function> {
            val result = ArrayList<Function>()
            for (member in members) {
                if (member is Function) {
                    result.add(member)
                }
            }
            return result
        }
    }

    class WorldDeclaration : Declaration {
        private val packageName: String?
        private val name: String
        private val includeDeclarations: List<IncludeDeclaration>
        private val declarations: List<Declaration>
        private val imports: List<WorldItem>
        private val exports: List<WorldItem>

        constructor(
            packageName: String?,
            name: String,
            imports: List<WorldItem>,
            exports: List<WorldItem>,
        ) : this(packageName, name, emptyList(), emptyList(), imports, exports)

        constructor(
            packageName: String?,
            name: String,
            includeDeclarations: List<IncludeDeclaration>,
            imports: List<WorldItem>,
            exports: List<WorldItem>,
        ) : this(packageName, name, includeDeclarations, emptyList(), imports, exports)

        constructor(
            packageName: String?,
            name: String,
            includeDeclarations: List<IncludeDeclaration>,
            declarations: List<Declaration>,
            imports: List<WorldItem>,
            exports: List<WorldItem>,
        ) {
            this.packageName = packageName?.let(::requireName)
            this.name = requireName(name)
            this.includeDeclarations = immutableList(includeDeclarations)
            this.declarations = immutableList(declarations)
            this.imports = immutableList(imports)
            this.exports = immutableList(exports)
        }

        override fun name(): String = name

        override fun packageName(): String? = packageName

        fun imports(): List<WorldItem> = imports

        fun exports(): List<WorldItem> = exports

        fun declarations(): List<Declaration> = declarations

        fun includes(): List<String> {
            val result = ArrayList<String>()
            for (include in includeDeclarations) {
                result.add(include.path())
            }
            return immutableList(result)
        }

        fun includeDeclarations(): List<IncludeDeclaration> = includeDeclarations
    }

    class IncludeDeclaration(path: String, items: List<IncludeItem>) {
        private val path: String = requireName(path)
        private val items: List<IncludeItem> = immutableList(items)
        private val aliases: Map<String, String>

        init {
            val aliases = LinkedHashMap<String, String>()
            for (item in items) {
                aliases[item.name()] = item.alias()
            }
            this.aliases = immutableMap(aliases)
        }

        fun path(): String = path

        fun items(): List<IncludeItem> = items

        fun aliasFor(name: String): String? {
            val alias = aliases[name]
            if (alias != null) {
                return alias
            }
            return aliases[WitNames.lastSegment(name)]
        }
    }

    class IncludeItem(name: String, alias: String) {
        private val name: String = requireName(name)
        private val alias: String = requireName(alias)

        fun name(): String = name

        fun alias(): String = alias
    }

    class WorldItem(direction: Direction, name: String, function: Function?, type: TypeRef?) {
        private val direction: Direction = direction
        private val name: String = requireName(name)
        private val function: Function? = function
        private val type: TypeRef? = type
        val isFunction: Boolean
            get() = function != null

        fun direction(): Direction = direction

        fun name(): String = name

        fun function(): Function? = function

        fun type(): TypeRef? = type
    }

    enum class Direction {
        IMPORT,
        EXPORT,
    }

    class Function
    @ComponentModelJvmOverloads
    constructor(
        name: String,
        parameters: List<Field>,
        results: List<Field>,
        private val async: Boolean = false,
        private val staticFunction: Boolean = false,
        private val constructor: Boolean = false,
    ) : InterfaceMember {
        private val name: String = requireName(name)
        private val parameters: List<Field> = immutableList(parameters)
        private val results: List<Field> = immutableList(results)
        val isAsync: Boolean
            get() = async

        val isStatic: Boolean
            get() = staticFunction

        val isConstructor: Boolean
            get() = constructor

        override fun name(): String = name

        fun parameters(): List<Field> = parameters

        fun results(): List<Field> = results
    }

    class TypeDeclaration
    @ComponentModelJvmOverloads
    constructor(
        kind: Kind,
        name: String,
        fields: List<Field>,
        cases: List<Case>,
        target: TypeRef?,
        functions: List<Function> = emptyList(),
    ) : InterfaceMember {
        private val kind: Kind = kind
        private val name: String = requireName(name)
        private val fields: List<Field> = immutableList(fields)
        private val cases: List<Case> = immutableList(cases)
        private val target: TypeRef? = target
        private val functions: List<Function> = immutableList(functions)

        override fun name(): String = name

        fun kind(): Kind = kind

        fun fields(): List<Field> = fields

        fun cases(): List<Case> = cases

        fun target(): TypeRef? = target

        fun functions(): List<Function> = functions

        enum class Kind {
            RECORD,
            VARIANT,
            ENUM,
            FLAGS,
            RESOURCE,
            ALIAS,
        }
    }

    class Field(name: String, type: TypeRef) {
        private val name: String = requireName(name)
        private val type: TypeRef = type

        fun name(): String = name

        fun type(): TypeRef = type
    }

    class Case(name: String, type: TypeRef?) {
        private val name: String = requireName(name)
        private val type: TypeRef? = type

        fun name(): String = name

        fun type(): TypeRef? = type
    }

    class TypeRef
    private constructor(
        private val kind: TypeKind,
        private val name: String?,
        arguments: List<TypeRef>,
    ) {
        private val arguments: List<TypeRef> = immutableList(arguments)

        fun kind(): TypeKind = kind

        fun name(): String? = name

        fun arguments(): List<TypeRef> = arguments

        override fun toString(): String {
            if (name != null) {
                return name
            }
            return kind.name.lowercase() + arguments
        }

        enum class TypeKind {
            PRIMITIVE,
            NAMED,
            LIST,
            OPTION,
            RESULT,
            TUPLE,
            FUTURE,
            STREAM,
            BORROW,
            OWN,
        }

        companion object {
            @ComponentModelJvmStatic
            fun named(name: String): TypeRef =
                TypeRef(TypeKind.NAMED, requireName(name), emptyList())

            @ComponentModelJvmStatic
            fun primitive(name: String): TypeRef =
                TypeRef(TypeKind.PRIMITIVE, requireName(name), emptyList())

            @ComponentModelJvmStatic
            fun constructed(kind: TypeKind, arguments: List<TypeRef>): TypeRef =
                TypeRef(kind, null, arguments)
        }
    }

    private class Parser(source: String) {
        private val tokens: List<Token> = Tokenizer(source).tokenize()
        private val syntheticDeclarations = ArrayList<Declaration>()
        private val typeScopes = ArrayList<TypeScope>()
        private var pos = 0
        private var packageName: String? = null
        private var currentPackageName: String? = null

        fun parse(): WitPackage {
            val declarations = parseDeclarationsUntil(null)
            declarations.addAll(syntheticDeclarations)
            return WitPackage(packageName, resolveWorldIncludes(declarations))
        }

        private fun parseDeclarationsUntil(closingToken: String?): ArrayList<Declaration> {
            val declarations = ArrayList<Declaration>()
            while (!eof()) {
                skipAttributes()
                if (closingToken != null && consume(closingToken)) {
                    return declarations
                }
                if (consume("package")) {
                    parsePackageDeclaration(declarations)
                    continue
                }
                if (consume("use")) {
                    declarations.add(parseUseDeclaration())
                    continue
                }

                val declaration = parseDeclaration()
                if (declaration != null) {
                    declarations.add(declaration)
                } else {
                    pos++
                }
            }
            if (closingToken != null) {
                throw error("unterminated package block")
            }
            return declarations
        }

        private fun parsePackageDeclaration(declarations: MutableList<Declaration>) {
            val name = collectPackageName()
            if (name.isNotEmpty() && packageName == null) {
                packageName = name
            }
            if (consume("{")) {
                val previousPackageName = currentPackageName
                if (name.isNotEmpty()) {
                    currentPackageName = name
                }
                declarations.addAll(parseDeclarationsUntil("}"))
                currentPackageName = previousPackageName
            } else {
                if (name.isNotEmpty()) {
                    currentPackageName = name
                }
                expect(";")
            }
        }

        private fun resolveWorldIncludes(declarations: List<Declaration>): List<Declaration> {
            val worlds = LinkedHashMap<String, WorldDeclaration>()
            for (declaration in declarations) {
                if (declaration is WorldDeclaration) {
                    indexWorld(worlds, declaration)
                }
            }

            val cache = LinkedHashMap<String, WorldDeclaration>()
            val result = ArrayList<Declaration>()
            for (declaration in declarations) {
                if (declaration is WorldDeclaration) {
                    result.add(resolveWorldInclude(declaration, worlds, cache, LinkedHashSet()))
                } else {
                    result.add(declaration)
                }
            }
            return result
        }

        private fun resolveWorldInclude(
            world: WorldDeclaration,
            worlds: Map<String, WorldDeclaration>,
            cache: MutableMap<String, WorldDeclaration>,
            resolving: MutableSet<String>,
        ): WorldDeclaration {
            val cacheKey = worldCacheKey(world)
            val cached = cache[cacheKey]
            if (cached != null) {
                return cached
            }
            if (!resolving.add(cacheKey)) {
                throw error("cyclic WIT world include involving " + world.qualifiedName())
            }

            val declarations = ArrayList<Declaration>()
            val imports = ArrayList<WorldItem>()
            val exports = ArrayList<WorldItem>()
            for (include in world.includeDeclarations()) {
                val included = findIncludedWorld(worlds, world, include.path())
                if (included == null) {
                    throw error(
                        "unknown included WIT world " + include.path() + " in " + world.name()
                    )
                }
                val resolved = resolveWorldInclude(included, worlds, cache, resolving)
                declarations.addAll(remapIncludedDeclarations(resolved.declarations(), include))
                imports.addAll(remapIncludedItems(resolved.imports(), include))
                exports.addAll(remapIncludedItems(resolved.exports(), include))
            }
            declarations.addAll(world.declarations())
            imports.addAll(world.imports())
            exports.addAll(world.exports())

            resolving.remove(cacheKey)
            val resolved =
                WorldDeclaration(
                    world.packageName(),
                    world.name(),
                    world.includeDeclarations(),
                    declarations,
                    imports,
                    exports,
                )
            cache[cacheKey] = resolved
            return resolved
        }

        private fun worldCacheKey(world: WorldDeclaration): String {
            val qualifiedName = world.qualifiedName()
            return if (qualifiedName.isBlank()) world.name() else qualifiedName
        }

        private fun indexWorld(
            worlds: MutableMap<String, WorldDeclaration>,
            world: WorldDeclaration,
        ) {
            val qualifiedName = world.qualifiedName()
            putWorld(worlds, qualifiedName, world, true)
            putWorld(worlds, WitNames.withoutVersion(qualifiedName), world, true)
            putWorld(worlds, world.name(), world, false)
            putWorld(worlds, WitNames.lastSegment(world.name()), world, false)
        }

        private fun putWorld(
            worlds: MutableMap<String, WorldDeclaration>,
            key: String?,
            world: WorldDeclaration,
            exact: Boolean,
        ) {
            if (key == null || key.isBlank()) {
                return
            }
            if (exact) {
                worlds[key] = world
            } else if (!worlds.containsKey(key)) {
                worlds[key] = world
            }
        }

        private fun remapIncludedDeclarations(
            declarations: List<Declaration>,
            include: IncludeDeclaration,
        ): List<Declaration> {
            if (include.items().isEmpty()) {
                return declarations
            }
            val result = ArrayList<Declaration>()
            for (declaration in declarations) {
                result.add(remapIncludedDeclaration(declaration, include))
            }
            return result
        }

        private fun remapIncludedDeclaration(
            declaration: Declaration,
            include: IncludeDeclaration,
        ): Declaration {
            if (declaration is TypeDeclaration) {
                val alias = include.aliasFor(declaration.name())
                if (alias == null) {
                    return declaration
                }
                return TypeDeclaration(
                    declaration.kind(),
                    alias,
                    declaration.fields(),
                    declaration.cases(),
                    declaration.target(),
                    declaration.functions(),
                )
            }
            return declaration
        }

        private fun remapIncludedItems(
            items: List<WorldItem>,
            include: IncludeDeclaration,
        ): List<WorldItem> {
            if (include.items().isEmpty()) {
                return items
            }
            val result = ArrayList<WorldItem>()
            for (item in items) {
                result.add(remapIncludedItem(item, include))
            }
            return result
        }

        private fun remapIncludedItem(item: WorldItem, include: IncludeDeclaration): WorldItem {
            var alias = include.aliasFor(item.name())
            val type = item.type()
            val typeName = type?.name()
            if (alias == null && typeName != null) {
                alias = include.aliasFor(typeName)
            }
            if (alias == null) {
                return item
            }
            return WorldItem(item.direction(), alias, item.function(), item.type())
        }

        private fun findIncludedWorld(
            worlds: Map<String, WorldDeclaration>,
            includingWorld: WorldDeclaration,
            include: String,
        ): WorldDeclaration? {
            val candidates = ArrayList<String>()
            if (!include.contains("/") && !include.contains(":")) {
                val qualified =
                    WitNames.qualifiedInterfaceName(includingWorld.packageName(), include)
                addIncludeCandidate(candidates, qualified)
                addIncludeCandidate(candidates, WitNames.withoutVersion(qualified))
            }
            addIncludeCandidate(candidates, include)
            addIncludeCandidate(candidates, WitNames.withoutVersion(include))
            addIncludeCandidate(candidates, WitNames.lastSegment(include))

            for (candidate in candidates) {
                val world = worlds[candidate]
                if (world != null) {
                    return world
                }
            }
            return null
        }

        private fun addIncludeCandidate(candidates: MutableList<String>, candidate: String?) {
            if (candidate != null && candidate.isNotBlank() && !candidates.contains(candidate)) {
                candidates.add(candidate)
            }
        }

        private fun parseDeclaration(): Declaration? {
            if (consume("interface")) {
                return parseInterface()
            }
            if (consume("world")) {
                return parseWorld()
            }
            return parseTypeDeclaration()
        }

        private fun parseInterface(): InterfaceDeclaration {
            val name = expectIdentifier()
            return parseInterfaceBody(name)
        }

        private fun parseInterfaceBody(name: String): InterfaceDeclaration {
            expect("{")
            val members = ArrayList<InterfaceMember>()
            val scope = TypeScope(currentPackageName, name)
            typeScopes.add(scope)
            try {
                while (!consume("}")) {
                    skipAttributes()
                    if (eof()) {
                        throw error("unterminated interface $name")
                    }
                    if (consume("use")) {
                        val use = parseUseDeclaration()
                        scope.addUse(use)
                        members.add(use)
                        continue
                    }
                    val type = parseTypeDeclaration()
                    if (type != null) {
                        scope.addType(type.name())
                        members.add(type)
                        continue
                    }
                    val memberName = expectIdentifier()
                    if (consume(":")) {
                        val function = parseFunctionAfterColon(memberName)
                        if (function != null) {
                            members.add(function)
                        } else {
                            skipUntil(";")
                        }
                    } else {
                        skipUntil(";")
                    }
                }
                return InterfaceDeclaration(currentPackageName, name, members)
            } finally {
                typeScopes.removeAt(typeScopes.lastIndex)
            }
        }

        private fun parseWorld(): WorldDeclaration {
            val name = expectIdentifier()
            expect("{")
            val includes = ArrayList<IncludeDeclaration>()
            val declarations = ArrayList<Declaration>()
            val imports = ArrayList<WorldItem>()
            val exports = ArrayList<WorldItem>()
            while (!consume("}")) {
                skipAttributes()
                if (eof()) {
                    throw error("unterminated world $name")
                }
                if (consume("use")) {
                    declarations.add(parseUseDeclaration())
                    continue
                }
                if (consume("include")) {
                    val includePath = collectIncludePath()
                    val includeItems = ArrayList<IncludeItem>()
                    if (consume("with")) {
                        expect("{")
                        while (!consume("}")) {
                            val itemName = collectIncludeItemName()
                            expect("as")
                            includeItems.add(IncludeItem(itemName, collectIncludeItemName()))
                            consume(",")
                        }
                    }
                    includes.add(IncludeDeclaration(includePath, includeItems))
                    consume(";")
                    continue
                }

                val declaration = parseTypeDeclaration()
                if (declaration != null) {
                    declarations.add(declaration)
                    continue
                }

                val direction =
                    if (consume("import")) {
                        Direction.IMPORT
                    } else if (consume("export")) {
                        Direction.EXPORT
                    } else {
                        skipUntil(";")
                        continue
                    }

                var itemName = expectIdentifier()
                var function: Function? = null
                var type: TypeRef? = null
                if (consume(":")) {
                    if (consume("interface")) {
                        val inlineInterfaceName = inlineInterfaceName(name, itemName)
                        syntheticDeclarations.add(parseInterfaceBody(inlineInterfaceName))
                        type = TypeRef.named(inlineInterfaceName)
                    } else {
                        function = parseFunctionAfterColon(itemName)
                        if (function == null) {
                            type = parseTypeRef()
                            if (isUnaliasedQualifiedPath(itemName, type)) {
                                itemName = itemName + ":" + type.name()
                                type = TypeRef.named(itemName)
                            }
                        } else {
                            type = null
                        }
                    }
                } else {
                    type = TypeRef.named(itemName)
                }
                if (consume("as")) {
                    itemName = expectIdentifier()
                }
                consume(";")

                val item = WorldItem(direction, itemName, function, type)
                if (direction == Direction.IMPORT) {
                    imports.add(item)
                } else {
                    exports.add(item)
                }
            }
            return WorldDeclaration(
                currentPackageName,
                name,
                includes,
                declarations,
                imports,
                exports,
            )
        }

        private fun inlineInterfaceName(worldName: String, itemName: String): String =
            "$worldName-$itemName"

        private fun parseUseDeclaration(): UseDeclaration {
            val path = collectUsePath()
            val items = ArrayList<UseItem>()
            if (consume("{")) {
                while (!consume("}")) {
                    val itemName = expectIdentifier()
                    var alias: String? = null
                    if (consume("as")) {
                        alias = expectIdentifier()
                    }
                    items.add(UseItem(itemName, alias))
                    consume(",")
                }
            } else {
                var alias: String? = null
                if (consume("as")) {
                    alias = expectIdentifier()
                }
                items.add(UseItem(WitNames.lastSegment(path), alias))
            }
            consume(";")
            return UseDeclaration(path, items)
        }

        private fun collectUsePath(): String {
            val result = StringBuilder()
            while (!eof() && !peek("{") && !peek(";") && !peek("as")) {
                result.append(next().text())
            }
            var path = result.toString()
            while (path.endsWith(".")) {
                path = path.substring(0, path.length - 1)
            }
            return path
        }

        private fun collectIncludePath(): String {
            val result = StringBuilder()
            while (!eof() && !peek(";") && !peek("with")) {
                result.append(next().text())
            }
            return result.toString()
        }

        private fun collectIncludeItemName(): String {
            val result = StringBuilder()
            while (!eof() && !peek("as") && !peek(",") && !peek("}")) {
                result.append(next().text())
            }
            return result.toString()
        }

        private fun isUnaliasedQualifiedPath(itemName: String, type: TypeRef): Boolean {
            val typeName = type.name() ?: return false
            return type.kind() == TypeRef.TypeKind.NAMED &&
                !typeName.contains(":") &&
                (typeName.contains("/") || typeName.contains("@")) &&
                !itemName.contains("/") &&
                !itemName.contains("@")
        }

        private fun parseTypeDeclaration(): TypeDeclaration? {
            if (consume("record")) {
                return parseRecord()
            }
            if (consume("variant")) {
                return parseVariant()
            }
            if (consume("enum")) {
                return parseEnum()
            }
            if (consume("flags")) {
                return parseFlags()
            }
            if (consume("resource")) {
                return parseResource()
            }
            if (consume("type")) {
                return parseAlias()
            }
            return null
        }

        private fun parseRecord(): TypeDeclaration {
            val name = expectIdentifier()
            expect("{")
            val fields = ArrayList<Field>()
            while (!consume("}")) {
                skipAttributes()
                val fieldName = expectIdentifier()
                expect(":")
                fields.add(Field(fieldName, parseTypeRef()))
                consume(",")
                consume(";")
            }
            return TypeDeclaration(TypeDeclaration.Kind.RECORD, name, fields, emptyList(), null)
        }

        private fun parseVariant(): TypeDeclaration {
            val name = expectIdentifier()
            expect("{")
            val cases = ArrayList<Case>()
            while (!consume("}")) {
                skipAttributes()
                val caseName = expectIdentifier()
                var type: TypeRef? = null
                if (consume("(")) {
                    type = parseTypeRef()
                    expect(")")
                } else if (consume(":")) {
                    type = parseTypeRef()
                }
                cases.add(Case(caseName, type))
                consume(",")
                consume(";")
            }
            return TypeDeclaration(TypeDeclaration.Kind.VARIANT, name, emptyList(), cases, null)
        }

        private fun parseEnum(): TypeDeclaration = parseCasesOnly(TypeDeclaration.Kind.ENUM)

        private fun parseFlags(): TypeDeclaration = parseCasesOnly(TypeDeclaration.Kind.FLAGS)

        private fun parseCasesOnly(kind: TypeDeclaration.Kind): TypeDeclaration {
            val name = expectIdentifier()
            expect("{")
            val cases = ArrayList<Case>()
            while (!consume("}")) {
                skipAttributes()
                cases.add(Case(expectIdentifier(), null))
                consume(",")
                consume(";")
            }
            return TypeDeclaration(kind, name, emptyList(), cases, null)
        }

        private fun parseResource(): TypeDeclaration {
            val name = expectIdentifier()
            val functions = ArrayList<Function>()
            if (consume("{")) {
                while (!consume("}")) {
                    skipAttributes()
                    if (eof()) {
                        throw error("unterminated resource $name")
                    }
                    if (consume("constructor")) {
                        functions.add(
                            Function(
                                "constructor",
                                parseFieldsInParens("arg"),
                                emptyList(),
                                false,
                                false,
                                true,
                            )
                        )
                        consume(";")
                        continue
                    }
                    val functionName = expectIdentifier()
                    if (consume(":")) {
                        val function = parseFunctionAfterColon(functionName)
                        if (function != null) {
                            functions.add(function)
                        } else {
                            skipUntil(";")
                        }
                    } else {
                        skipUntil(";")
                    }
                }
            } else {
                consume(";")
            }
            return TypeDeclaration(
                TypeDeclaration.Kind.RESOURCE,
                name,
                emptyList(),
                emptyList(),
                null,
                functions,
            )
        }

        private fun parseAlias(): TypeDeclaration {
            val name = expectIdentifier()
            expect("=")
            val target = parseTypeRef()
            consume(";")
            return TypeDeclaration(
                TypeDeclaration.Kind.ALIAS,
                name,
                emptyList(),
                emptyList(),
                target,
            )
        }

        private fun parseFunction(name: String, async: Boolean, staticFunction: Boolean): Function {
            val params = parseFieldsInParens("arg")
            val results = ArrayList<Field>()
            if (consume("->")) {
                if (peek("(")) {
                    results.addAll(parseFieldsInParens("result"))
                } else {
                    results.add(Field("result", parseTypeRef()))
                }
            }
            consume(";")
            return Function(name, params, results, async, staticFunction, false)
        }

        private fun parseFunctionAfterColon(name: String): Function? {
            var async = false
            var staticFunction = false
            while (true) {
                if (consume("async")) {
                    async = true
                } else if (consume("static")) {
                    staticFunction = true
                } else {
                    break
                }
            }
            if (!consume("func")) {
                return null
            }
            return parseFunction(name, async, staticFunction)
        }

        private fun parseFieldsInParens(prefix: String): ArrayList<Field> {
            expect("(")
            val fields = ArrayList<Field>()
            while (!consume(")")) {
                val first = expectIdentifier()
                val type: TypeRef
                val name: String
                if (consume(":")) {
                    name = first
                    type = parseTypeRef()
                } else {
                    name = prefix + fields.size
                    type = typeFromName(first)
                }
                fields.add(Field(name, type))
                consume(",")
            }
            return fields
        }

        private fun parseTypeRef(): TypeRef {
            if (consume("(")) {
                val types = ArrayList<TypeRef>()
                while (!consume(")")) {
                    if (lookAheadIsField()) {
                        expectIdentifier()
                        expect(":")
                    }
                    types.add(parseTypeRef())
                    consume(",")
                }
                return TypeRef.constructed(TypeRef.TypeKind.TUPLE, types)
            }

            var name = expectIdentifier()
            while (consume(":")) {
                name = name + ":" + expectIdentifier()
            }
            if (consume("<")) {
                val arguments = ArrayList<TypeRef>()
                while (!consume(">")) {
                    if (consume("_")) {
                        arguments.add(TypeRef.primitive("unit"))
                    } else if (lookAheadIsField()) {
                        expectIdentifier()
                        expect(":")
                        arguments.add(parseTypeRef())
                    } else {
                        arguments.add(parseTypeRef())
                    }
                    consume(",")
                }
                return TypeRef.constructed(constructedKind(name), arguments)
            }
            return typeFromName(name)
        }

        private fun lookAheadIsField(): Boolean = isIdentifier(peekText()) && peek(1, ":")

        private fun typeFromName(name: String): TypeRef {
            val normalized = WitNames.stripIdentifierEscape(name)
            return when (normalized) {
                "bool",
                "s8",
                "s16",
                "s32",
                "s64",
                "u8",
                "u16",
                "u32",
                "u64",
                "f32",
                "f64",
                "char",
                "string",
                "unit" -> TypeRef.primitive(normalized)

                "result" -> TypeRef.constructed(TypeRef.TypeKind.RESULT, emptyList())
                "future" -> TypeRef.constructed(TypeRef.TypeKind.FUTURE, emptyList())
                "stream" -> TypeRef.constructed(TypeRef.TypeKind.STREAM, emptyList())
                else -> TypeRef.named(normalized)
                    .let { named ->
                        activeTypeScope()?.resolve(normalized)?.let(TypeRef::named) ?: named
                    }
            }
        }

        private fun activeTypeScope(): TypeScope? =
            if (typeScopes.isEmpty()) null else typeScopes[typeScopes.lastIndex]

        private class TypeScope(packageName: String?, interfaceName: String) {
            private val interfacePath = WitNames.qualifiedInterfaceName(packageName, interfaceName)
            private val aliases = LinkedHashMap<String, String>()

            fun addType(name: String) {
                val normalized = WitNames.stripIdentifierEscape(name)
                aliases[normalized] = qualifiedTypeName(interfacePath, normalized)
            }

            fun addUse(use: UseDeclaration) {
                val interfacePath =
                    WitNames.qualifiedInterfaceName(
                        packageName = packageName(),
                        interfaceName = use.path(),
                    )
                for (item in use.items()) {
                    aliases[WitNames.stripIdentifierEscape(item.localName())] =
                        qualifiedTypeName(interfacePath, item.name())
                }
            }

            fun resolve(name: String): String? {
                if (name.contains("/") || name.contains(":") || name.contains(".")) {
                    return null
                }
                return aliases[WitNames.stripIdentifierEscape(name)]
            }

            private fun packageName(): String? {
                val slash = interfacePath.indexOf('/')
                if (slash < 0) {
                    return null
                }
                val at = interfacePath.indexOf('@', slash)
                return if (at >= 0) {
                    interfacePath.substring(0, slash) + interfacePath.substring(at)
                } else {
                    interfacePath.substring(0, slash)
                }
            }

            private fun qualifiedTypeName(interfacePath: String, typeName: String): String =
                "$interfacePath/${WitNames.stripIdentifierEscape(typeName)}"
        }

        private fun constructedKind(name: String): TypeRef.TypeKind =
            when (WitNames.stripIdentifierEscape(name)) {
                "list" -> TypeRef.TypeKind.LIST
                "option" -> TypeRef.TypeKind.OPTION
                "result" -> TypeRef.TypeKind.RESULT
                "tuple" -> TypeRef.TypeKind.TUPLE
                "future" -> TypeRef.TypeKind.FUTURE
                "stream" -> TypeRef.TypeKind.STREAM
                "borrow" -> TypeRef.TypeKind.BORROW
                "own" -> TypeRef.TypeKind.OWN
                else -> throw error("unsupported constructed WIT type $name")
            }

        private fun skipAttributes() {
            while (peekText().startsWith("@")) {
                pos++
                if (consume("(")) {
                    var depth = 1
                    while (!eof() && depth > 0) {
                        if (consume("(")) {
                            depth++
                        } else if (consume(")")) {
                            depth--
                        } else {
                            pos++
                        }
                    }
                }
            }
        }

        private fun skipBlock() {
            var depth = 1
            while (!eof() && depth > 0) {
                if (consume("{")) {
                    depth++
                } else if (consume("}")) {
                    depth--
                } else {
                    pos++
                }
            }
        }

        private fun collectPackageName(): String {
            val result = StringBuilder()
            while (!eof() && !peek(";") && !peek("{")) {
                result.append(next().text())
            }
            return result.toString()
        }

        private fun skipUntil(token: String) {
            while (!eof() && !consume(token)) {
                if (consume("{")) {
                    skipBlock()
                } else {
                    pos++
                }
            }
        }

        private fun expectIdentifier(): String {
            val token = next()
            if (!isIdentifier(token.text())) {
                throw error("expected identifier, got " + token.text())
            }
            return WitNames.stripIdentifierEscape(token.text())
        }

        private fun expect(text: String) {
            if (!consume(text)) {
                throw error("expected $text, got " + peekText())
            }
        }

        private fun consume(text: String): Boolean {
            if (peek(text)) {
                pos++
                return true
            }
            return false
        }

        private fun peek(text: String): Boolean = peek(0, text)

        private fun peek(offset: Int, text: String): Boolean =
            (pos + offset) < tokens.size && tokens[pos + offset].text() == text

        private fun peekText(): String = if (eof()) "<eof>" else tokens[pos].text()

        private fun next(): Token {
            if (eof()) {
                throw error("unexpected end of input")
            }
            return tokens[pos++]
        }

        private fun eof(): Boolean = pos >= tokens.size

        private fun error(message: String): IllegalArgumentException =
            IllegalArgumentException(message)
    }

    private class Tokenizer(source: String) {
        private val source: String = source
        private val tokens = ArrayList<Token>()
        private var pos = 0

        fun tokenize(): List<Token> {
            while (pos < source.length) {
                val ch = source[pos]
                if (ch.isWhitespace()) {
                    pos++
                } else if (startsWith("//")) {
                    skipLine()
                } else if (startsWith("/*")) {
                    skipBlockComment()
                } else {
                    tokenizeNext()
                }
            }
            return tokens.toList()
        }

        private fun tokenizeNext() {
            for (token in TWO_CHAR_TOKENS) {
                if (startsWith(token)) {
                    tokens.add(Token(token))
                    pos += token.length
                    return
                }
            }

            val ch = source[pos]
            if (ONE_CHAR_TOKENS.indexOf(ch) >= 0) {
                tokens.add(Token(ch.toString()))
                pos++
                return
            }

            val start = pos
            while (pos < source.length && isIdentifierChar(source[pos])) {
                pos++
            }
            if (start == pos) {
                tokens.add(Token(ch.toString()))
                pos++
            } else {
                tokens.add(Token(source.substring(start, pos)))
            }
        }

        private fun isIdentifierChar(ch: Char): Boolean =
            ch.isLetterOrDigit() ||
                ch == '_' ||
                ch == '-' ||
                ch == '%' ||
                ch == '@' ||
                ch == '/' ||
                ch == '.'

        private fun skipLine() {
            while (pos < source.length && source[pos] != '\n') {
                pos++
            }
        }

        private fun skipBlockComment() {
            pos += 2
            while (pos < source.length && !startsWith("*/")) {
                pos++
            }
            if (startsWith("*/")) {
                pos += 2
            }
        }

        private fun startsWith(text: String): Boolean = source.startsWith(text, pos)

        companion object {
            private val TWO_CHAR_TOKENS = listOf("->")
            private const val ONE_CHAR_TOKENS = "{}():,;<>="
        }
    }

    private class Token(private val text: String) {
        fun text(): String = text
    }

    companion object {
        @ComponentModelJvmStatic fun parse(source: String): WitPackage = Parser(source).parse()

        private fun requireName(name: String): String {
            val result = name.trim()
            if (result.isEmpty()) {
                throw IllegalArgumentException("name must not be empty")
            }
            return WitNames.stripIdentifierEscape(result)
        }

        private fun isIdentifier(text: String?): Boolean {
            if (text == null || text.isEmpty()) {
                return false
            }
            val first = text[0]
            return first.isLetter() || first == '_' || first == '%' || first == '@'
        }

        private fun <T> immutableList(values: List<T>): List<T> = values.toList()

        private fun <K, V> immutableMap(values: Map<K, V>): Map<K, V> = values.toMap()
    }
}
