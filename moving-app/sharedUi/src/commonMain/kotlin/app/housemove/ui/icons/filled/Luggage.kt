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

public val Icons.Filled.Luggage: ImageVector
    get() {
        if (_luggage != null) {
            return _luggage!!
        }
        _luggage = materialIcon(name = "Filled.Luggage") {
            materialPath {
                moveTo(17.0f, 6.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineTo(3.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-4.0f)
                curveTo(9.45f, 2.0f, 9.0f, 2.45f, 9.0f, 3.0f)
                verticalLineToRelative(3.0f)
                horizontalLineTo(7.0f)
                curveTo(5.9f, 6.0f, 5.0f, 6.9f, 5.0f, 8.0f)
                verticalLineToRelative(11.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.0f, 0.55f, 0.45f, 1.0f, 1.0f, 1.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                verticalLineTo(8.0f)
                curveTo(19.0f, 6.9f, 18.1f, 6.0f, 17.0f, 6.0f)
                close()
                moveTo(9.5f, 18.0f)
                horizontalLineTo(8.0f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(1.5f)
                verticalLineTo(18.0f)
                close()
                moveTo(12.75f, 18.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(9.0f)
                horizontalLineToRelative(1.5f)
                verticalLineTo(18.0f)
                close()
                moveTo(13.5f, 6.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineTo(3.5f)
                horizontalLineToRelative(3.0f)
                verticalLineTo(6.0f)
                close()
                moveTo(16.0f, 18.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineTo(9.0f)
                horizontalLineTo(16.0f)
                verticalLineTo(18.0f)
                close()
            }
        }
        return _luggage!!
    }

private var _luggage: ImageVector? = null
