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

public val Icons.Filled.NoPhotography: ImageVector
    get() {
        if (_noPhotography != null) {
            return _noPhotography!!
        }
        _noPhotography = materialIcon(name = "Filled.NoPhotography") {
            materialPath {
                moveTo(10.94f, 8.12f)
                lineTo(7.48f, 4.66f)
                lineTo(9.0f, 3.0f)
                horizontalLineToRelative(6.0f)
                lineToRelative(1.83f, 2.0f)
                horizontalLineTo(20.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, 0.9f, 2.0f, 2.0f)
                verticalLineToRelative(12.0f)
                curveToRelative(0.0f, 0.05f, -0.01f, 0.1f, -0.02f, 0.16f)
                lineToRelative(-5.1f, -5.1f)
                curveTo(16.96f, 13.71f, 17.0f, 13.36f, 17.0f, 13.0f)
                curveToRelative(0.0f, -2.76f, -2.24f, -5.0f, -5.0f, -5.0f)
                curveTo(11.64f, 8.0f, 11.29f, 8.04f, 10.94f, 8.12f)
                close()
                moveTo(20.49f, 23.31f)
                lineTo(18.17f, 21.0f)
                horizontalLineTo(4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -0.59f, 0.27f, -1.12f, 0.68f, -1.49f)
                lineToRelative(-2.0f, -2.0f)
                lineTo(2.1f, 2.1f)
                lineToRelative(19.8f, 19.8f)
                lineTo(20.49f, 23.31f)
                close()
                moveTo(14.49f, 17.32f)
                lineToRelative(-1.5f, -1.5f)
                curveTo(12.67f, 15.92f, 12.35f, 16.0f, 12.0f, 16.0f)
                curveToRelative(-1.66f, 0.0f, -3.0f, -1.34f, -3.0f, -3.0f)
                curveToRelative(0.0f, -0.35f, 0.08f, -0.67f, 0.19f, -0.98f)
                lineToRelative(-1.5f, -1.5f)
                curveTo(7.25f, 11.24f, 7.0f, 12.09f, 7.0f, 13.0f)
                curveToRelative(0.0f, 2.76f, 2.24f, 5.0f, 5.0f, 5.0f)
                curveTo(12.91f, 18.0f, 13.76f, 17.75f, 14.49f, 17.32f)
                close()
            }
        }
        return _noPhotography!!
    }

private var _noPhotography: ImageVector? = null
