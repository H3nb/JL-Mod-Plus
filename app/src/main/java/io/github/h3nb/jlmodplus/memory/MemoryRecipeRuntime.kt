/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package io.github.h3nb.jlmodplus.memory

import android.os.Bundle
import java.lang.ref.WeakReference
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import javax.microedition.shell.MemoryDiscoveryBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Target-process, on-demand Memory Recipe executor. No language interpreter, persistent object
 * handles, class loading, or graph-wide search. A recipe consists of verified guest-field paths
 * and bounded primitive writes; all write targets are resolved before the first mutation.
 *
 * Freeze uses the existing Memory Editor tick, not a second timer. A held recipe is invalidated
 * rather than silently retargeted when any target owner is replaced.
 */
class MemoryRecipeRuntime {
    private data class Problem(val resultCode: Int, override val message: String) :
        RuntimeException(message)

    private data class Root(val kind: String, val className: String)
    private data class Step(
        val kind: String, val owner: String = "", val name: String = "",
        val descriptor: String = "", val index: Int? = null, val indexInput: String? = null,
        val start: Int = 0, val length: Int = 0,
    )
    private data class Action(
        val kind: String, val root: Root, val path: List<Step>, val input: String?,
        val literal: Long?, val shift: Int, val width: Int, val arrayLength: Int?,
        val nonZeroMask: Long, val constant: Long, val identityMask: Long,
        val operation: String, val field: String?,
    )
    private data class Recipe(
        val id: String, val name: String, val mainClass: String, val actions: List<Action>,
        val inputs: Map<String, LongRange>, val mode: String,
    )
    private data class Cell(
        val owner: Any, val field: Field?, val index: Int,
        val type: Class<*>,
    ) {
        fun read(): Long {
            val value = if (field == null) ReflectArray.get(owner, index) else field.get(
                if (owner is Class<*>) null else owner
            )
            return when (value) {
                is Byte -> value.toLong()
                is Short -> value.toLong()
                is Int -> value.toLong()
                is Long -> value
                is Char -> value.code.toLong()
                is Boolean -> if (value) 1L else 0L
                else -> throw Problem(MemoryEngineContract.RESULT_UNSUPPORTED, "Non-integral target")
            }
        }

        fun validate(value: Long) {
            when (type) {
                java.lang.Byte.TYPE -> checkRange(value, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong())
                java.lang.Short.TYPE -> checkRange(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                java.lang.Integer.TYPE -> checkRange(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                java.lang.Character.TYPE -> checkRange(value, 0L, 65535L)
                java.lang.Boolean.TYPE -> if (value != 0L && value != 1L) throw Problem(
                    MemoryEngineContract.RESULT_INVALID_REQUEST, "Boolean requires 0 or 1"
                )
                java.lang.Long.TYPE -> Unit
                else -> throw Problem(MemoryEngineContract.RESULT_UNSUPPORTED, "Unsupported write type")
            }
        }

        fun write(value: Long) {
            validate(value)
            val boxed: Any = when (type) {
                java.lang.Byte.TYPE -> {
                    checkRange(value, Byte.MIN_VALUE.toLong(), Byte.MAX_VALUE.toLong())
                    value.toByte()
                }
                java.lang.Short.TYPE -> {
                    checkRange(value, Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                    value.toShort()
                }
                java.lang.Integer.TYPE -> {
                    checkRange(value, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    value.toInt()
                }
                java.lang.Long.TYPE -> value
                java.lang.Character.TYPE -> {
                    checkRange(value, 0L, 65535L)
                    value.toInt().toChar()
                }
                java.lang.Boolean.TYPE -> {
                    if (value != 0L && value != 1L) throw Problem(
                        MemoryEngineContract.RESULT_INVALID_REQUEST, "Boolean requires 0 or 1"
                    )
                    value != 0L
                }
                else -> throw Problem(MemoryEngineContract.RESULT_UNSUPPORTED, "Unsupported write type")
            }
            if (field == null) ReflectArray.set(owner, index, boxed)
            else field.set(if (owner is Class<*>) null else owner, boxed)
        }
    }
    private data class Write(val cell: Cell, val value: Long, val expected: Long)
    private data class Prepared(
        val writes: List<Write>, val owners: List<Any>, val readValues: List<String>,
        val identities: List<Long?>,
    )
    private data class Hold(
        val recipe: Recipe, val inputs: Map<String, Long>,
        val owners: List<WeakReference<Any>>, val identities: List<Long?>,
    )
    private data class FormSnapshot(
        val ticket: String, val token: Long, val recipe: Recipe,
        val selectors: Map<String, Long>, val owners: List<WeakReference<Any>>,
    )
    private val frozen = LinkedHashMap<String, Hold>()
    private var frozenToken = 0L
    private var formSnapshot: FormSnapshot? = null
    private var formReadSequence = 0L

    @Synchronized
    fun activeCount(token: Long): Int = if (token == frozenToken) frozen.size else 0

    @Synchronized
    fun activeIds(token: Long): Array<String> =
        if (token == frozenToken) frozen.keys.toTypedArray() else emptyArray()

    @Synchronized
    fun activeNames(token: Long): Array<String> =
        if (token == frozenToken) frozen.values.map { it.recipe.name }.toTypedArray()
        else emptyArray()

    @Synchronized
    fun runtimeClosed(token: Long) {
        if (formSnapshot?.token == token) formSnapshot = null
        if (token == frozenToken) {
            frozen.clear()
            frozenToken = 0L
        }
    }

    @Synchronized
    fun clear(token: Long, id: String): Bundle {
        if (!MemoryDiscoveryBridge.isActive(token)) return reply(
            token, MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet session ended"
        )
        val stopped = token == frozenToken && frozen.remove(id) != null
        return reply(token, MemoryEngineContract.RESULT_OK,
            if (stopped) "Freeze stopped" else "No active recipe Freeze")
    }

    @JvmOverloads
    @Synchronized
    fun run(
        token: Long, json: String?, parameters: Bundle?, hold: Boolean, preview: Boolean,
        ordinaryFreezeCount: Int = 0,
    ): Bundle {
        try {
            if (json == null || json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) bad("Recipe is missing or too large")
            val recipe = parse(json)
            if (!preview && recipe.mode == "toggle" && !hold)
                bad("Toggle scripts must be enabled with Freeze")
            if (!preview && hold && recipe.mode != "toggle" && recipe.mode != "legacy")
                bad("Only Toggle scripts can hold a recipe")
            val values = readParameters(recipe, parameters, readOnly = preview)
            val snapshot = currentSnapshot(token, recipe.mainClass)
            val prepared = prepare(snapshot, recipe, values, readOnly = preview)
            if (!preview && recipe.mode == "form") {
                val expected = parameters?.getStringArray(EXPECTED_VALUES)
                    ?: bad("Refresh the form before applying changes")
                val ticket = parameters.getString(READ_TICKET)
                    ?: bad("Refresh the form before applying changes")
                val read = formSnapshot
                if (read == null || read.ticket != ticket || read.token != token ||
                    read.recipe != recipe || read.selectors.any { (key, value) -> values[key] != value } ||
                    read.owners.size != prepared.owners.size ||
                    read.owners.indices.any { read.owners[it].get() !== prepared.owners[it] }) {
                    throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                        "Form target changed since Refresh; refresh before applying")
                }
                formSnapshot = null
                if (expected.size != prepared.readValues.size ||
                    !expected.contentEquals(prepared.readValues.toTypedArray())) {
                    throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                        "Values changed since Refresh; refresh before applying")
                }
            }
            if (preview) {
                val result = reply(token, MemoryEngineContract.RESULT_OK, "Read ${recipe.actions.size} logical values")
                result.putStringArray("recipeReadInputs",
                    recipe.actions.mapIndexed { index, action ->
                        action.field ?: action.input ?: "value ${index + 1}"
                    }.toTypedArray())
                result.putStringArray("recipeReadValues", prepared.readValues.toTypedArray())
                if (recipe.mode == "form") {
                    val ticket = "$token:${++formReadSequence}"
                    formSnapshot = FormSnapshot(
                        ticket, token, recipe, values,
                        prepared.owners.map { WeakReference(it) },
                    )
                    result.putString(READ_TICKET, ticket)
                }
                return result
            }
            if (hold) {
                val held = frozenToken == token && frozen.containsKey(recipe.id)
                val existingCount = if (frozenToken == token) frozen.size else 0
                if (!held && (existingCount >= MAX_HELD_RECIPES ||
                        ordinaryFreezeCount + existingCount >= MemoryEngineContract.MAX_FREEZE_RECORDS)) {
                    throw Problem(MemoryEngineContract.RESULT_RESOURCE_LIMIT, "Recipe Freeze limit reached")
                }
            }
            write(token, prepared)
            if (hold) {
                if (frozenToken != token) {
                    frozen.clear()
                    frozenToken = token
                }
                frozen[recipe.id] = Hold(
                    recipe, values, prepared.owners.map { WeakReference(it) }, prepared.identities,
                )
            }
            return reply(token, MemoryEngineContract.RESULT_OK, "Wrote ${prepared.writes.size} values")
        } catch (problem: Problem) {
            return reply(token, problem.resultCode, problem.message)
        } catch (error: ReflectiveOperationException) {
            return reply(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Guest field could not be accessed")
        } catch (error: RuntimeException) {
            return reply(token, MemoryEngineContract.RESULT_INVALID_REQUEST, "Invalid recipe or guest state")
        } catch (error: LinkageError) {
            return reply(token, MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Guest class linkage changed")
        }
    }

    /** Called from the existing 750 ms scheduler; a failed hold is stopped, not retargeted. */
    @Synchronized
    fun tick(token: Long): Bundle {
        if (token != frozenToken || frozen.isEmpty()) return reply(token, MemoryEngineContract.RESULT_OK, null)
        var written = 0
        var contested = 0
        var stopped = 0
        val iterator = frozen.entries.iterator()
        while (iterator.hasNext()) {
            val held = iterator.next().value
            try {
                val snapshot = currentSnapshot(token, held.recipe.mainClass)
                val prepared = prepare(snapshot, held.recipe, held.inputs)
                if (prepared.owners.size != held.owners.size ||
                    prepared.owners.indices.any { held.owners[it].get() !== prepared.owners[it] } ||
                    prepared.identities != held.identities
                ) throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Recipe target was replaced")
                if (write(token, prepared, strictReadback = false)) written++
                else contested++
            } catch (error: Problem) {
                iterator.remove()
                stopped++
            } catch (error: ReflectiveOperationException) {
                iterator.remove()
                stopped++
            } catch (error: RuntimeException) {
                iterator.remove()
                stopped++
            } catch (error: LinkageError) {
                iterator.remove()
                stopped++
            }
        }
        return reply(token,
            if (stopped > 0 || contested > 0) MemoryEngineContract.RESULT_PARTIAL_WRITE
            else MemoryEngineContract.RESULT_OK,
            "Recipe Freeze tick: $written confirmed, $contested contested, $stopped stopped")
    }

    private fun write(
        token: Long, prepared: Prepared, strictReadback: Boolean = true,
    ): Boolean {
        var written = 0
        var contested = false
        try {
            // A one-shot write rejects a changed source. Freeze is different: the game's
            // modifications are the very changes it must counter, not a lost target.
            if (strictReadback) for (item in prepared.writes) {
                if (!MemoryDiscoveryBridge.isActive(token)) throw Problem(
                    MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet session ended before recipe write"
                )
                if (item.cell.read() != item.expected) throw Problem(
                    MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Value changed before recipe write"
                )
            }
            for (item in prepared.writes) {
                if (!MemoryDiscoveryBridge.isActive(token)) throw Problem(
                    MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet session ended during recipe write"
                )
                item.cell.write(item.value)
                written++
                if (item.cell.read() != item.value) {
                    if (strictReadback) throw Problem(
                        MemoryEngineContract.RESULT_PARTIAL_WRITE, "Recipe readback disagrees"
                    )
                    contested = true // A concurrent game update can win this tick; retry next tick.
                }
            }
            return !contested
        } catch (error: Problem) {
            if (written > 0) throw Problem(MemoryEngineContract.RESULT_PARTIAL_WRITE,
                "Recipe changed $written values before failure: ${error.message}")
            throw error
        } catch (error: RuntimeException) {
            if (written > 0) throw Problem(MemoryEngineContract.RESULT_PARTIAL_WRITE,
                "Recipe changed $written values before runtime failure")
            throw error
        } catch (error: ReflectiveOperationException) {
            if (written > 0) throw Problem(MemoryEngineContract.RESULT_PARTIAL_WRITE,
                "Recipe changed $written values before access failure")
            throw error
        }
    }

    private fun prepare(
        snapshot: MemoryDiscoveryBridge.Snapshot, recipe: Recipe,
        inputs: Map<String, Long>, readOnly: Boolean = false,
    ): Prepared {
        val writes = ArrayList<Write>()
        val owners = ArrayList<Any>()
        val readValues = ArrayList<String>()
        val identities = ArrayList<Long?>()
        val logicalValues = HashMap<String, String>()
        val requestedStatics = recipe.actions.asSequence()
            .filter { it.root.kind == "initializedStatic" }
            .map { it.root.className }
            .toSet()
        // The discovery snapshot clones its class registry; do so once per operation rather
        // than for each action of a compound Freeze (e.g. four encoded currency targets).
        val staticRoots = if (requestedStatics.isEmpty()) emptyMap() else snapshot.classes()
            .asSequence()
            .filter { it.classLoader === snapshot.loader() && internalName(it) in requestedStatics }
            .associateBy(::internalName)
        for (action in recipe.actions) {
            val cells = resolve(snapshot, action.root, action.path, inputs, staticRoots)
            if (cells.isEmpty() || cells.size > 4) bad("Action has no target or exceeds a byte slice")
            val actual = cells.map { it.read() }
            val array = cells[0].owner
            if (action.arrayLength != null &&
                (!array.javaClass.isArray || ReflectArray.getLength(array) != action.arrayLength)
            ) throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Array length changed")
            val currentLogical = when (action.kind) {
                "set" -> actual.singleOrNull()?.toString() ?: bad("Expected scalar")
                "bitfield" -> {
                    val bits = when (cells.singleOrNull()?.type) {
                        java.lang.Byte.TYPE -> 8
                        java.lang.Short.TYPE, java.lang.Character.TYPE -> 16
                        java.lang.Integer.TYPE -> 32
                        else -> bad("Bitfield requires byte, short, char or int")
                    }
                    if (action.width !in 1..31 || action.shift !in 0..31 ||
                        action.width + action.shift > bits) bad("Invalid bitfield")
                    ((actual[0] ushr action.shift) and ((1L shl action.width) - 1L)).toString()
                }
                "encoded32le" -> {
                    if (cells.size != 4 || cells.any { it.type != java.lang.Byte.TYPE })
                        bad("Expected four bytes")
                    var bits = 0L
                    for (i in 0..3) bits = bits or ((actual[i] and 0xFFL) shl (i * 8))
                    try {
                        Math.subtractExact(action.constant, bits.toInt().toLong()).toString()
                    } catch (error: ArithmeticException) {
                        bad("Decoded value exceeds the supported integer range")
                    }
                }
                else -> bad("Unsupported action")
            }
            readValues.add(currentLogical)
            if (!readOnly && (recipe.mode == "form" || action.operation == "add")) {
                val key = action.field ?: action.input
                if (key != null) {
                    val previous = logicalValues[key]
                    if (previous == null) logicalValues[key] = currentLogical
                    else if (previous != currentLogical) {
                        throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE,
                            "Inconsistent copies of $key")
                    }
                }
            }
            if (readOnly) {
                cells.forEach { owners.add(it.owner) }
                continue // Read needs locator parameters, not the proposed edit.
            }
            val operand = number(action, inputs)
            val logicalWrite = if (action.operation == "add") {
                try {
                    Math.addExact(currentLogical.toLong(), operand)
                } catch (error: ArithmeticException) {
                    bad("Addition exceeds the supported integer range")
                }
            } else operand
            val expectedWrite = when (action.kind) {
                "set" -> listOf(logicalWrite)
                "bitfield" -> {
                    val bits = when (cells[0].type) {
                        java.lang.Byte.TYPE -> 8
                        java.lang.Short.TYPE, java.lang.Character.TYPE -> 16
                        java.lang.Integer.TYPE -> 32
                        else -> bad("Bitfield requires byte, short, char or int")
                    }
                    if (cells.size != 1 || action.width !in 1..31 ||
                        action.shift !in 0..31 || action.width + action.shift > bits) bad("Invalid bitfield")
                    val valueMask = (1L shl bits) - 1L
                    if (action.nonZeroMask < 0 || action.nonZeroMask and valueMask.inv() != 0L)
                        bad("Bitfield guard exceeds primitive width")
                    val raw = actual[0] and valueMask
                    if (action.nonZeroMask != 0L && raw and action.nonZeroMask == 0L) {
                        throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Empty or nonmatching slot")
                    }
                    val newValue = logicalWrite
                    val fieldMask = ((1L shl action.width) - 1L) shl action.shift
                    if (newValue < 0 || newValue >= (1L shl action.width)) bad("Bitfield value out of range")
                    val masked = (raw and fieldMask.inv()) or (newValue shl action.shift)
                    listOf(when (cells[0].type) {
                        java.lang.Byte.TYPE -> masked.toByte().toLong()
                        java.lang.Short.TYPE -> masked.toShort().toLong()
                        java.lang.Character.TYPE -> (masked and 0xFFFFL)
                        else -> masked.toInt().toLong()
                    })
                }
                "encoded32le" -> {
                    if (cells.size != 4 || cells.any { it.type != java.lang.Byte.TYPE }) bad("Expected four bytes")
                    val value = try {
                        Math.subtractExact(action.constant, logicalWrite)
                    } catch (error: ArithmeticException) {
                        bad("Encoded value overflow")
                    }
                    if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) bad("Encoded value overflow")
                    (0..3).map { ((value ushr (it * 8)) and 0xFF).toByte().toLong() }
                }
                else -> bad("Unsupported action")
            }
            if (cells.size != expectedWrite.size) bad("Action target width disagrees")
            identities.add(if (action.identityMask != 0L) {
                if (action.kind != "bitfield" || cells.size != 1)
                    bad("Identity mask is only supported for a bitfield")
                val typeBits = when (cells[0].type) {
                    java.lang.Byte.TYPE -> 8
                    java.lang.Short.TYPE, java.lang.Character.TYPE -> 16
                    java.lang.Integer.TYPE -> 32
                    else -> bad("Unsupported bitfield identity type")
                }
                val validMask = (1L shl typeBits) - 1
                val editMask = ((1L shl action.width) - 1) shl action.shift
                if (action.identityMask < 0 ||
                    action.identityMask and validMask.inv() != 0L ||
                    action.identityMask and editMask != 0L)
                    bad("Identity mask must preserve bits outside the edited value")
                actual[0] and action.identityMask
            } else null)
            for (i in cells.indices) {
                val cell = cells[i]
                if (writes.any { it.cell.owner === cell.owner && it.cell.field == cell.field &&
                        it.cell.index == cell.index }) bad("Overlapping recipe writes")
                // Prohibit modifications of final fields; a final array reference is traversable.
                if (cell.field != null && Modifier.isFinal(cell.field.modifiers)) bad("Final field cannot be written")
                cell.validate(expectedWrite[i])
                writes.add(Write(cell, expectedWrite[i], actual[i]))
                owners.add(cell.owner)
            }
        }
        if (writes.size > MAX_WRITES) bad("Recipe write budget exceeded")
        return Prepared(writes, owners, readValues, identities)
    }

    private fun resolve(snapshot: MemoryDiscoveryBridge.Snapshot, root: Root, path: List<Step>,
                        inputs: Map<String, Long>, staticRoots: Map<String, Class<*>>): List<Cell> {
        var current: Any = when (root.kind) {
            "midlet" -> snapshot.root() ?: throw Problem(MemoryEngineContract.RESULT_TARGET_LOST, "MIDlet not ready")
            "displayable" -> snapshot.displayableRoot()
                ?: throw Problem(MemoryEngineContract.RESULT_TARGET_LOST, "Displayable not ready")
            "initializedStatic" -> staticRoots[root.className]
                ?: throw Problem(MemoryEngineContract.RESULT_TARGET_LOST, "Static class not initialized")
            else -> bad("Unsupported root")
        }
        if (internalName(if (current is Class<*>) current else current.javaClass) != root.className) {
            throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Root class differs")
        }
        for ((position, step) in path.withIndex()) {
            val last = position == path.lastIndex
            when (step.kind) {
                "field" -> {
                    val actual = if (current is Class<*>) current else current.javaClass
                    if (actual.classLoader !== snapshot.loader()) {
                        throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Owner is not a guest class")
                    }
                    var declaring: Class<*>? = actual
                    while (declaring != null && internalName(declaring) != step.owner) {
                        declaring = declaring.superclass
                    }
                    if (declaring == null || declaring.classLoader !== snapshot.loader()) {
                        throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Declaring class differs")
                    }
                    val candidates = declaring.declaredFields.filter {
                        it.name == step.name && descriptor(it.type) == step.descriptor &&
                            Modifier.isStatic(it.modifiers) == (current is Class<*>)
                    }
                    if (candidates.size != 1) throw Problem(
                        MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Exact field signature not found"
                    )
                    val field = candidates.single()
                    field.isAccessible = true
                    if (last) return listOf(Cell(current, field, -1, field.type))
                    current = field.get(if (current is Class<*>) null else current)
                        ?: throw Problem(MemoryEngineContract.RESULT_TARGET_LOST, "Null guest reference")
                }
                "index", "slice" -> {
                    if (!current.javaClass.isArray ||
                        descriptor(current.javaClass) != step.descriptor) throw Problem(
                        MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Array descriptor differs"
                    )
                    val length = ReflectArray.getLength(current)
                    val start = if (step.kind == "slice") step.start else step.indexInput?.let {
                        val value = inputs[it] ?: bad("Missing index input")
                        if (value < 0 || value > 1_999_999) bad("Array index out of range")
                        value.toInt()
                    } ?: step.index ?: bad("Missing array index")
                    val count = if (step.kind == "slice") step.length else 1
                    if (start < 0 || count < 1 || count > 4 || start.toLong() + count > length) {
                        throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Array bound changed")
                    }
                    if (last) {
                        val component = current.javaClass.componentType
                        if (!component.isPrimitive) bad("Terminal array is not primitive")
                        return (start until start + count).map { Cell(current, null, it, component) }
                    }
                    if (count != 1 || current !is Array<*>) bad("Intermediate element is not an object")
                    current = current[start]
                        ?: throw Problem(MemoryEngineContract.RESULT_TARGET_LOST, "Null array element")
                }
                else -> bad("Unsupported path step")
            }
        }
        bad("Recipe has no terminal target")
    }

    private fun currentSnapshot(token: Long, mainClass: String): MemoryDiscoveryBridge.Snapshot {
        val snapshot = MemoryDiscoveryBridge.snapshot(token)
        if (!snapshot.isAvailable() || snapshot.loader() == null || snapshot.root() == null) {
            throw Problem(MemoryEngineContract.RESULT_TARGET_LOST, "Managed guest runtime unavailable")
        }
        if (internalName(snapshot.root().javaClass) != mainClass) {
            throw Problem(MemoryEngineContract.RESULT_IDENTITY_UNSAFE, "Recipe targets a different MIDlet")
        }
        return snapshot
    }

    private fun parse(json: String): Recipe {
        val obj = Json.parseToJsonElement(json) as? JsonObject ?: bad("Expected JSON object")
        obj.expectOnly("formatVersion", "id", "name", "game", "inputs", "root", "actions", "mode")
        val version = obj.int("formatVersion")
        if (version !in 1..2) bad("Unsupported recipe version")
        val mode = if (version == 1) {
            if (obj.containsKey("mode")) bad("Mode requires recipe v2")
            "legacy"
        } else obj.string("mode").also {
            if (it !in setOf("toggle", "form", "oneShot")) bad("Unsupported recipe mode")
        }
        val id = obj.string("id")
        if (id.length !in 1..64 || !id.matches(Regex("[A-Za-z0-9_.-]+"))) bad("Invalid recipe ID")
        val name = obj["name"]?.let {
            (it as? JsonPrimitive)?.content?.takeIf { value ->
                value.length in 1..80 && value.isNotBlank() &&
                    value.none { char -> Character.isISOControl(char) }
            }
                ?: bad("Invalid recipe name")
        } ?: id
        val game = obj.objectAt("game")
        game.expectOnly("mainClass", "sourceJarSha256")
        val mainClass = game.string("mainClass")
        if (!validGuestClassName(mainClass)) bad("Invalid main class")
        val specs = obj["inputs"]?.let {
            it as? JsonObject ?: bad("Inputs must be an object")
        } ?: JsonObject(emptyMap())
        if (specs.size > MAX_INPUTS || specs.keys.any { key ->
                (key == EXPECTED_VALUES || key == READ_TICKET) ||
                key.isEmpty() || key.length > 64 ||
                    key.any { char -> Character.isISOControl(char) }
            }) bad("Invalid input declarations")
        val inputRanges = specs.mapValues { (_, v) ->
            val s = v as? JsonObject ?: bad("Invalid input")
            s.expectOnly("min", "max", "default", "label")
            s["label"]?.let {
                val label = (it as? JsonPrimitive)?.content ?: bad("Invalid input label")
                if (label.length !in 1..80 || label.any(Character::isISOControl))
                    bad("Invalid input label")
            }
            val min = s.long("min")
            val max = s.long("max")
            if (max < min) bad("Input range is inverted")
            if (s["default"] != null && s.long("default") !in min..max)
                bad("Input default outside declared range")
            min..max
        }
        val defaultRoot = obj["root"]?.let {
            parseRoot(it as? JsonObject ?: bad("Root must be an object"))
        }
        val actions = obj["actions"] as? JsonArray ?: bad("Missing actions")
        if (actions.isEmpty() || actions.size > MAX_WRITES) bad("Invalid action count")
        val parsed = actions.map { element ->
            val a = element as? JsonObject ?: bad("Invalid action")
            a.expectOnly("kind", "root", "path", "valueInput", "value",
                "shift", "width", "arrayLength", "nonZeroMask", "constant", "identityMask",
                "operation", "field")
            val root = a["root"]?.let {
                parseRoot(it as? JsonObject ?: bad("Action root must be an object"))
            } ?: defaultRoot
                ?: bad("Action has no root")
            val path = a["path"] as? JsonArray ?: bad("Missing action path")
            if (path.isEmpty() || path.size > 24) bad("Invalid path depth")
            val steps = path.map { element ->
                val s = element as? JsonObject ?: bad("Invalid step")
                when (val kind = s.string("kind")) {
                    "field" -> {
                        s.expectOnly("kind", "owner", "name", "descriptor")
                        Step(kind, s.string("owner"), s.string("name"), s.string("descriptor"))
                    }
                    "index" -> {
                        s.expectOnly("kind", "descriptor", "index", "indexInput")
                        if (s.containsKey("index") == s.containsKey("indexInput")) bad("Choose one index source")
                        Step(kind, descriptor = s.string("descriptor"),
                        index = s["index"]?.let { (it as? JsonPrimitive)?.content?.toIntOrNull() ?: bad("Bad index") },
                        indexInput = s["indexInput"]?.let { (it as? JsonPrimitive)?.content ?: bad("Bad index input") })
                    }
                    "slice" -> {
                        s.expectOnly("kind", "descriptor", "start", "length")
                        Step(kind, descriptor = s.string("descriptor"),
                            start = s.int("start"), length = s.int("length"))
                    }
                    else -> bad("Unknown path step")
                }
            }
            if (steps.any { it.indexInput != null && it.indexInput !in inputRanges })
                bad("Undeclared index input")
            val kind = a.string("kind")
            if (kind !in setOf("set", "bitfield", "encoded32le")) bad("Unsupported operation")
            val input = a["valueInput"]?.let { (it as? JsonPrimitive)?.content ?: bad("Bad value input") }
            val literal = a["value"]?.let { parseNumber((it as? JsonPrimitive)?.content ?: bad("Bad literal")) }
            if ((input == null) == (literal == null)) bad("Exactly one value source is required")
            if (input != null && input !in inputRanges) bad("Undeclared input")
            val operation = a["operation"]?.let { a.string("operation") } ?: "set"
            if (operation !in setOf("set", "add") || (operation == "add" && mode != "oneShot"))
                bad("Addition is only supported in a one-shot v2 recipe")
            if (version == 1 && (a.containsKey("operation") || a.containsKey("field")))
                bad("Action extensions require recipe v2")
            val field = a["field"]?.let { a.string("field") }
            if (field != null && !field.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
                bad("Invalid logical field")
            if (operation == "add" && field == null) bad("Addition requires a logical field")
            if (mode == "form" && input != null && field != null && field != input)
                bad("Form field must match its value input")
            Action(kind, root, steps, input, literal,
                a["shift"]?.let { a.int("shift") } ?: 0,
                a["width"]?.let { a.int("width") } ?: 0,
                a["arrayLength"]?.let { a.int("arrayLength") },
                a["nonZeroMask"]?.let { parseNumber(a.string("nonZeroMask")) } ?: 0L,
                a["constant"]?.let { parseNumber(a.string("constant")) } ?: 0L,
                a["identityMask"]?.let { parseNumber(a.string("identityMask")) } ?: 0L,
                operation, field)
        }
        val relativeFields = parsed.filter { it.operation == "add" }
            .mapNotNull(Action::field).toSet()
        if (parsed.any { it.field in relativeFields && it.operation != "add" })
            bad("A logical field cannot mix addition with replacement")
        if (mode == "form") {
            val selectors = parsed.flatMap { action ->
                action.path.mapNotNull(Step::indexInput)
            }.toSet()
            val editable = parsed.mapNotNull(Action::input).toSet()
            if (editable.isEmpty() || (selectors intersect editable).isNotEmpty() ||
                (selectors + editable) != inputRanges.keys) {
                bad("Form requires distinct selector and editable inputs")
            }
        }
        if (mode == "toggle" && specs.values.any { value ->
                !(value as JsonObject).containsKey("default")
            }) bad("Toggle inputs require default values")
        return Recipe(id, name, mainClass, parsed, inputRanges, mode)
    }

    private fun parseRoot(obj: JsonObject): Root {
        obj.expectOnly("kind", "class")
        val kind = obj.string("kind")
        val clazz = obj.string("class")
        if (kind !in setOf("midlet", "displayable", "initializedStatic") ||
            !validGuestClassName(clazz)) bad("Invalid root")
        return Root(kind, clazz)
    }

    private fun readParameters(
        recipe: Recipe, bundle: Bundle?, readOnly: Boolean = false,
    ): Map<String, Long> {
        val values = HashMap<String, Long>()
        if (bundle != null && bundle.keySet().any {
                it !in recipe.inputs && it != EXPECTED_VALUES && it != READ_TICKET
            })
            bad("Unexpected input")
        val needed = if (readOnly) recipe.actions.flatMap { action ->
            action.path.mapNotNull(Step::indexInput)
        }.toSet() else recipe.inputs.keys
        for ((name, range) in recipe.inputs) {
            if (name !in needed) continue
            val text = bundle?.getString(name) ?: bad("Missing input: $name")
            val value = parseNumber(text)
            if (value !in range) bad("Input $name outside declared range")
            values[name] = value
        }
        return values
    }

    private fun number(action: Action, inputs: Map<String, Long>): Long =
        action.input?.let { inputs[it] ?: bad("Missing numeric input") }
            ?: action.literal ?: bad("Missing action value")

    private fun reply(token: Long, code: Int, detail: String?): Bundle = Bundle().apply {
        putLong(MemoryEngineContract.KEY_RUNTIME_TOKEN, token)
        putInt(MemoryEngineContract.KEY_MANAGED_OPERATION_RESULT, code)
        putInt("recipeFreezeCount", activeCount(token))
        if (detail != null) putString(MemoryEngineContract.KEY_DIAGNOSTIC_DETAIL,
            MemoryEngineContract.truncateMessage(detail))
    }

    // JVM internal names may use Unicode; an ASCII-only check excludes legitimate MIDlets.
    private fun validGuestClassName(name: String): Boolean =
        name.length in 1..200 && !name.startsWith('/') && !name.endsWith('/') &&
            !name.contains("//") && name.none { char ->
                char == '.' || char == ';' || char == '[' || Character.isISOControl(char)
            }

    private fun JsonObject.expectOnly(vararg allowed: String) {
        if (keys.any { it !in allowed }) bad("Unsupported recipe property")
    }

    private fun JsonObject.string(name: String): String =
        (this[name] as? JsonPrimitive)?.content ?: bad("Missing $name")

    private fun JsonObject.int(name: String): Int = string(name).toIntOrNull() ?: bad("Invalid $name")
    private fun JsonObject.long(name: String): Long = parseNumber(string(name))
    private fun JsonObject.objectAt(name: String): JsonObject =
        this[name] as? JsonObject ?: bad("Expected $name object")

    private fun parseNumber(text: String): Long = try {
        if (text.startsWith("0x", true)) text.substring(2).toLong(16) else text.toLong()
    } catch (error: NumberFormatException) { bad("Invalid integer constant") }

    private fun internalName(type: Class<*>): String = type.name.replace('.', '/')
    private fun descriptor(type: Class<*>): String = when {
        type.isArray -> type.name.replace('.', '/')
        type == java.lang.Byte.TYPE -> "B"
        type == java.lang.Short.TYPE -> "S"
        type == java.lang.Integer.TYPE -> "I"
        type == java.lang.Long.TYPE -> "J"
        type == java.lang.Boolean.TYPE -> "Z"
        type == java.lang.Character.TYPE -> "C"
        type == java.lang.Float.TYPE -> "F"
        type == java.lang.Double.TYPE -> "D"
        else -> "L${type.name.replace('.', '/')};"
    }

    companion object {
        /** Validate a recipe without resolving or touching guest memory. */
        @JvmStatic
        fun validateDocument(json: String) {
            if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES)
                bad("Recipe exceeds 24 KiB")
            MemoryRecipeRuntime().parse(json)
        }

        private fun bad(detail: String): Nothing = throw Problem(
            MemoryEngineContract.RESULT_INVALID_REQUEST, detail
        )

        private fun checkRange(value: Long, minimum: Long, maximum: Long) {
            if (value !in minimum..maximum) bad("Value exceeds primitive range")
        }

        private const val MAX_JSON_BYTES = 24_576
        private const val MAX_WRITES = 64
        private const val MAX_INPUTS = 16
        private const val EXPECTED_VALUES = "recipeExpectedValues"
        private const val READ_TICKET = "recipeReadTicket"
        private const val MAX_HELD_RECIPES = 4
    }
}
