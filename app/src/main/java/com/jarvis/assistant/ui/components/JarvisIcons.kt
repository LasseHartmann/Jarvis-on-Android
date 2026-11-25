package com.jarvis.assistant.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Lightweight, cohesive minimalist 24dp vector icons (replaces the 55MB material-icons-extended dependency).
 */
object JarvisIcons {

    private fun strokeIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply(block).build()

    private fun ImageVector.Builder.strokePath(vararg commands: () -> Unit) = Unit

    private val stroke = SolidColor(Color.White)

    val Key: ImageVector by lazy {
        strokeIcon("Key") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7.5f, 15.5f)
                arcToRelative(4f, 4f, 0f, true, true, 3.87f, -5f)
                horizontalLineTo(21f)
                verticalLineTo(13f)
                moveTo(17.5f, 10.5f)
                verticalLineTo(13f)
            }
        }
    }

    val Sparkle: ImageVector by lazy {
        strokeIcon("Sparkle") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                lineTo(14.2f, 9.8f)
                lineTo(21f, 12f)
                lineTo(14.2f, 14.2f)
                lineTo(12f, 21f)
                lineTo(9.8f, 14.2f)
                lineTo(3f, 12f)
                lineTo(9.8f, 9.8f)
                close()
            }
        }
    }

    val Shield: ImageVector by lazy {
        strokeIcon("Shield") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                lineTo(19f, 6f)
                verticalLineTo(11.5f)
                curveTo(19f, 16f, 16f, 19.5f, 12f, 21f)
                curveTo(8f, 19.5f, 5f, 16f, 5f, 11.5f)
                verticalLineTo(6f)
                close()
            }
        }
    }

    val Voice: ImageVector by lazy {
        strokeIcon("Voice") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 4f)
                arcToRelative(3f, 3f, 0f, false, false, -3f, 3f)
                verticalLineTo(12f)
                arcToRelative(3f, 3f, 0f, false, false, 6f, 0f)
                verticalLineTo(7f)
                arcToRelative(3f, 3f, 0f, false, false, -3f, -3f)
                close()
                moveTo(6f, 11.5f)
                arcToRelative(6f, 6f, 0f, false, false, 12f, 0f)
                moveTo(12f, 17.5f)
                verticalLineTo(21f)
            }
        }
    }

    val Palette: ImageVector by lazy {
        strokeIcon("Palette") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                arcToRelative(9f, 9f, 0f, true, false, 0f, 18f)
                horizontalLineTo(13.5f)
                arcToRelative(2.2f, 2.2f, 0f, false, false, 0f, -4.4f)
                horizontalLineTo(12f)
                arcToRelative(1.6f, 1.6f, 0f, false, true, 0f, -3.2f)
                horizontalLineTo(16.5f)
                arcToRelative(4.5f, 4.5f, 0f, false, false, 4.5f, -4.4f)
                arcTo(6f, 6f, 0f, false, false, 12f, 3f)
                close()
            }
        }
    }

    val Sliders: ImageVector by lazy {
        strokeIcon("Sliders") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f)
                moveTo(4f, 12f); horizontalLineTo(20f)
                moveTo(4f, 17f); horizontalLineTo(20f)
                moveTo(9f, 5f); verticalLineTo(9f)
                moveTo(15f, 10f); verticalLineTo(14f)
                moveTo(10f, 15f); verticalLineTo(19f)
            }
        }
    }

    val Bell: ImageVector by lazy {
        strokeIcon("Bell") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6f, 16f)
                horizontalLineTo(18f)
                lineTo(16.8f, 13.5f)
                verticalLineTo(10f)
                arcToRelative(4.8f, 4.8f, 0f, false, false, -9.6f, 0f)
                verticalLineTo(13.5f)
                close()
                moveTo(10f, 19f)
                arcToRelative(2f, 2f, 0f, false, false, 4f, 0f)
            }
        }
    }

    val Phone: ImageVector by lazy {
        strokeIcon("Phone") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(7.5f, 3f)
                horizontalLineTo(16.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, 1.5f, 1.5f)
                verticalLineTo(19.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, 1.5f)
                horizontalLineTo(7.5f)
                arcToRelative(1.5f, 1.5f, 0f, false, true, -1.5f, -1.5f)
                verticalLineTo(4.5f)
                arcTo(1.5f, 1.5f, 0f, false, true, 7.5f, 3f)
                close()
                moveTo(11f, 18f)
                horizontalLineTo(13f)
            }
        }
    }

    val Bookmark: ImageVector by lazy {
        strokeIcon("Bookmark") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(6.5f, 4f)
                horizontalLineTo(17.5f)
                verticalLineTo(20f)
                lineTo(12f, 16.5f)
                lineTo(6.5f, 20f)
                close()
            }
        }
    }

    val Clock: ImageVector by lazy {
        strokeIcon("Clock") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(12f, 3f)
                arcToRelative(9f, 9f, 0f, true, false, 0f, 18f)
                arcToRelative(9f, 9f, 0f, true, false, 0f, -18f)
                close()
                moveTo(12f, 7.5f)
                verticalLineTo(12f)
                lineTo(15f, 14f)
            }
        }
    }

    val Wave: ImageVector by lazy {
        strokeIcon("Wave") {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round) {
                moveTo(4f, 10f); verticalLineTo(14f)
                moveTo(8f, 7f); verticalLineTo(17f)
                moveTo(12f, 4f); verticalLineTo(20f)
                moveTo(16f, 7f); verticalLineTo(17f)
                moveTo(20f, 10f); verticalLineTo(14f)
            }
        }
    }

    val Speaker: ImageVector by lazy {
        strokeIcon("Speaker") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 9.5f)
                horizontalLineTo(8.5f)
                lineTo(13.5f, 5.5f)
                verticalLineTo(18.5f)
                lineTo(8.5f, 14.5f)
                horizontalLineTo(5f)
                close()
                moveTo(16.8f, 9f)
                arcToRelative(4.2f, 4.2f, 0f, false, true, 0f, 6f)
                moveTo(19.2f, 6.8f)
                arcToRelative(7.2f, 7.2f, 0f, false, true, 0f, 10.4f)
            }
        }
    }

    val ChevronRight: ImageVector by lazy {
        strokeIcon("ChevronRight") {
            path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(9.5f, 6.5f)
                lineTo(15f, 12f)
                lineTo(9.5f, 17.5f)
            }
        }
    }

    val Check: ImageVector by lazy {
        strokeIcon("Check") {
            path(stroke = stroke, strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(5f, 12.5f)
                lineTo(9.5f, 17f)
                lineTo(19f, 7.5f)
            }
        }
    }

    val Play: ImageVector by lazy {
        strokeIcon("Play") {
            path(fill = stroke, pathFillType = PathFillType.NonZero) {
                moveTo(8f, 6f)
                lineTo(18f, 12f)
                lineTo(8f, 18f)
                close()
            }
        }
    }

    val Eye: ImageVector by lazy {
        strokeIcon("Eye") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(2.5f, 12f)
                curveTo(5f, 7.5f, 8.5f, 6f, 12f, 6f)
                curveTo(15.5f, 6f, 19f, 7.5f, 21.5f, 12f)
                curveTo(19f, 16.5f, 15.5f, 18f, 12f, 18f)
                curveTo(8.5f, 18f, 5f, 16.5f, 2.5f, 12f)
                close()
                moveTo(12f, 9.5f)
                arcToRelative(2.5f, 2.5f, 0f, true, false, 0f, 5f)
                arcToRelative(2.5f, 2.5f, 0f, true, false, 0f, -5f)
                close()
            }
        }
    }

    val EyeOff: ImageVector by lazy {
        strokeIcon("EyeOff") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(3f, 3f)
                lineTo(21f, 21f)
                moveTo(10.5f, 6.2f)
                curveTo(11f, 6.1f, 11.5f, 6f, 12f, 6f)
                curveTo(15.5f, 6f, 19f, 7.5f, 21.5f, 12f)
                curveTo(20.6f, 13.7f, 19.4f, 15f, 18f, 16f)
                moveTo(6.2f, 8f)
                curveTo(4.7f, 9f, 3.5f, 10.3f, 2.5f, 12f)
                curveTo(5f, 16.5f, 8.5f, 18f, 12f, 18f)
                curveTo(13.3f, 18f, 14.5f, 17.8f, 15.6f, 17.3f)
            }
        }
    }

    val Trash: ImageVector by lazy {
        strokeIcon("Trash") {
            path(stroke = stroke, strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
                moveTo(4f, 7f); horizontalLineTo(20f)
                moveTo(9f, 7f); verticalLineTo(4.5f); horizontalLineTo(15f); verticalLineTo(7f)
                moveTo(6.5f, 7f); verticalLineTo(19.5f); horizontalLineTo(17.5f); verticalLineTo(7f)
                moveTo(10f, 11f); verticalLineTo(16f)
                moveTo(14f, 11f); verticalLineTo(16f)
            }
        }
    }
}
