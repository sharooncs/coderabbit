/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Copied from androidx.compose.material:material-icons-extended:1.7.8 (its sources jar). Changed: the package,
// which was androidx.compose.material.icons.filled. Licence: moving-app/licenses/Material-Icons-Apache-2.0.txt

package app.housemove.ui.icons.filled

import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.materialIcon
import app.housemove.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.PersonOff: ImageVector
    get() {
        if (_personOff != null) {
            return _personOff!!
        }
        _personOff = materialIcon(name = "Filled.PersonOff") {
            materialPath {
                moveTo(8.65f, 5.82f)
                curveTo(9.36f, 4.72f, 10.6f, 4.0f, 12.0f, 4.0f)
                curveToRelative(2.21f, 0.0f, 4.0f, 1.79f, 4.0f, 4.0f)
                curveToRelative(0.0f, 1.4f, -0.72f, 2.64f, -1.82f, 3.35f)
                lineTo(8.65f, 5.82f)
                close()
                moveTo(20.0f, 17.17f)
                curveToRelative(-0.02f, -1.1f, -0.63f, -2.11f, -1.61f, -2.62f)
                curveToRelative(-0.54f, -0.28f, -1.13f, -0.54f, -1.77f, -0.76f)
                lineTo(20.0f, 17.17f)
                close()
                moveTo(21.19f, 21.19f)
                lineTo(2.81f, 2.81f)
                lineTo(1.39f, 4.22f)
                lineToRelative(8.89f, 8.89f)
                curveToRelative(-1.81f, 0.23f, -3.39f, 0.79f, -4.67f, 1.45f)
                curveTo(4.61f, 15.07f, 4.0f, 16.1f, 4.0f, 17.22f)
                verticalLineTo(20.0f)
                horizontalLineToRelative(13.17f)
                lineToRelative(2.61f, 2.61f)
                lineTo(21.19f, 21.19f)
                close()
            }
        }
        return _personOff!!
    }

private var _personOff: ImageVector? = null
