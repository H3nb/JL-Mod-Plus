from pathlib import Path


def replace_once(path, old, new):
    p = Path(path)
    text = p.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}")
    p.write_text(text.replace(old, new, 1))


bubble = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEditorBubbleController.kt'
replace_once(
    bubble,
    '''            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                // rawX/rawY track the active pointer in screen coordinates and are not affected by
                // moving the View itself, which prevents the drag from feeding back into its delta.
                val deltaX = event.rawX - downRawX
                val deltaY = event.rawY - downRawY
''',
    '''            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId)
                if (index < 0) return true
                // Derive raw coordinates for the active pointer, not only pointer index 0. Using the
                // local-pointer delta keeps this compatible with API levels before getRawX(index).
                val deltaX = event.rawXForPointer(index) - downRawX
                val deltaY = event.rawYForPointer(index) - downRawY
''',
)
replace_once(
    bubble,
    '''            MotionEvent.ACTION_UP -> {
''',
    '''            MotionEvent.ACTION_POINTER_UP -> {
                val liftedIndex = event.actionIndex
                if (event.getPointerId(liftedIndex) == pointerId) {
                    val replacementIndex = (0 until event.pointerCount)
                        .firstOrNull { it != liftedIndex }
                    if (replacementIndex != null) {
                        pointerId = event.getPointerId(replacementIndex)
                        downRawX = event.rawXForPointer(replacementIndex)
                        downRawY = event.rawYForPointer(replacementIndex)
                        downViewX = bubbleView.x
                        downViewY = bubbleView.y
                    } else {
                        cancelDrag()
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
''',
)
replace_once(
    bubble,
    '''    private fun movementBounds(): RectF? {
''',
    '''    private fun MotionEvent.rawXForPointer(index: Int): Float =
        rawX + getX(index) - getX(0)

    private fun MotionEvent.rawYForPointer(index: Int): Float =
        rawY + getY(index) - getY(0)

    private fun movementBounds(): RectF? {
''',
)

compose = 'app/src/main/java/io/github/h3nb/jlmodplus/memory/MemoryEditorRuntimeCompose.kt'
replace_once(
    compose,
    '''                        onClick = {
                            editTargets = listOf(
                                MemoryEditTarget(row.id, row.primaryType, row.valueText, row.aliasTypes),
                            )
                            editRevision = state.revision
                        },
''',
    '''                        onClick = {
                            if (state.writeSupported) {
                                editTargets = listOf(
                                    MemoryEditTarget(row.id, row.primaryType, row.valueText, row.aliasTypes),
                                )
                                editRevision = state.revision
                            }
                        },
''',
)
replace_once(
    compose,
    '''                        onClick = {
                            editTargets = listOf(
                                MemoryEditTarget(row.id, row.type, row.valueText,
                                    listOf(row.type), watch = true),
                            )
                        },
''',
    '''                        onClick = {
                            if (state.writeSupported) {
                                editTargets = listOf(
                                    MemoryEditTarget(row.id, row.type, row.valueText,
                                        listOf(row.type), watch = true),
                                )
                            }
                        },
''',
)
