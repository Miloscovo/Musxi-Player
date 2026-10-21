package tech.musxi.core.playback

private const val INVALID_QUEUE_EDIT = "Invalid queue edit"

sealed interface QueueEdit {
    data class Remove(val index: Int) : QueueEdit

    data class Move(val from: Int, val to: Int) : QueueEdit

    data object Clear : QueueEdit
}

data class QueueEditPlan(
    val order: List<Int>,
    val currentIndex: Int?,
    val currentRemoved: Boolean,
)

object QueueEdits {
    fun plan(size: Int, currentIndex: Int, edit: QueueEdit): QueueEditPlan {
        require(size in 1..20_000 && currentIndex in 0 until size) {
            INVALID_QUEUE_EDIT
        }

        return when (edit) {
            is QueueEdit.Remove -> {
                require(edit.index in 0 until size) { INVALID_QUEUE_EDIT }
                planRemove(size, currentIndex, edit.index)
            }

            is QueueEdit.Move -> {
                require(edit.from in 0 until size && edit.to in 0 until size) {
                    INVALID_QUEUE_EDIT
                }
                planMove(size, currentIndex, edit.from, edit.to)
            }

            QueueEdit.Clear -> QueueEditPlan(
                order = emptyList(),
                currentIndex = null,
                currentRemoved = true,
            )
        }
    }

    private fun planRemove(size: Int, currentIndex: Int, removeIndex: Int): QueueEditPlan {
        if (removeIndex == currentIndex) {
            val nextIdentity = if (removeIndex < size - 1) {
                removeIndex + 1
            } else if (size > 1) {
                removeIndex - 1
            } else {
                null
            }
            val order = (0 until size).filter { it != removeIndex }
            return QueueEditPlan(
                order = order,
                currentIndex = nextIdentity?.let(order::indexOf),
                currentRemoved = true,
            )
        }

        val order = (0 until size).filter { it != removeIndex }
        return QueueEditPlan(
            order = order,
            currentIndex = order.indexOf(currentIndex),
            currentRemoved = false,
        )
    }

    private fun planMove(
        size: Int,
        currentIndex: Int,
        from: Int,
        to: Int,
    ): QueueEditPlan {
        val order = (0 until size).toMutableList()
        val movedIdentity = order.removeAt(from)
        order.add(to, movedIdentity)
        return QueueEditPlan(
            order = order,
            currentIndex = order.indexOf(currentIndex),
            currentRemoved = false,
        )
    }
}
