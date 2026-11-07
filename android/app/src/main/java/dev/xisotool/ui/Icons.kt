package dev.xisotool.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** A few Material-style icons drawn from path data (keeps the app free of the huge icon library). */
object XIcons {
    val Folder: ImageVector by lazy {
        vec("Folder", "M10,4H4C2.9,4 2.01,4.9 2.01,6L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val File: ImageVector by lazy {
        vec("File", "M14,2H6C4.9,2 4.01,2.9 4.01,4L4,20c0,1.1 0.89,2 1.99,2H18c1.1,0 2,-0.9 2,-2V8l-6,-6zM13,9V3.5L18.5,9H13z")
    }
    val Download: ImageVector by lazy {
        vec("Download", "M19,9h-4V3H9v6H5l7,7 7,-7zM5,18v2h14v-2H5z")
    }
    val Upload: ImageVector by lazy {
        vec("Upload", "M9,16h6v-6h4l-7,-7 -7,7h4zM5,18h14v2H5z")
    }
    val Disc: ImageVector by lazy {
        vec("Disc", "M12,2a10,10 0 1,0 0.001,0z M12,9a3,3 0 1,0 0.001,0z", PathFillType.EvenOdd)
    }
    val Swap: ImageVector by lazy {
        vec("Swap", "M6.99,11L3,15l3.99,4v-3H14v-2H6.99v-3zM21,9l-3.99,-4v3H10v2h7.01v3L21,9z")
    }
}

private fun vec(name: String, path: String, fill: PathFillType = PathFillType.NonZero): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            pathFillType = fill,
            fill = SolidColor(Color.Black),
        )
        .build()
