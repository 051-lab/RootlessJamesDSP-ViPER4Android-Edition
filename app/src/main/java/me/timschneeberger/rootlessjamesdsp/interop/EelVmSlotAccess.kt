package me.timschneeberger.rootlessjamesdsp.interop

import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable

/**
 * Slot-aware EEL VM access for the four local LiveProg engines.
 *
 * The remote/root AudioEffect engine deliberately has no EEL VM inspection API,
 * so it retains its existing unsupported behavior. Slot 0 remains compatible
 * with the original no-slot API surface.
 */
fun JamesDspBaseEngine.enumerateEelVariablesSlot(slot: Int): ArrayList<EelVmVariable> {
    if (slot !in 0..3)
        return arrayListOf()

    return when (this) {
        is JamesDspLocalEngine -> JamesDspWrapper.enumerateEelVariablesSlot(handle, slot)
        else -> if (slot == 0) enumerateEelVariables() else arrayListOf()
    }
}

/**
 * Updates a numeric variable in one LiveProg slot and executes that slot's
 * @slider section when the local native engine supports direct EEL VM access.
 */
fun JamesDspBaseEngine.manipulateEelVariableSlot(slot: Int, name: String, value: Float): Boolean {
    if (slot !in 0..3)
        return false

    return when (this) {
        is JamesDspLocalEngine -> JamesDspWrapper.manipulateEelVariableSlot(handle, slot, name, value)
        else -> slot == 0 && manipulateEelVariable(name, value)
    }
}
