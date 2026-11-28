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
// which was androidx.compose.material.icons.outlined. Licence: moving-app/licenses/Material-Icons-Apache-2.0.txt

package app.housemove.ui.icons.outlined

import app.housemove.ui.icons.Icons
import app.housemove.ui.icons.materialIcon
import app.housemove.ui.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.LocalShipping: ImageVector
    get() {
        if (_localShipping != null) {
            return _localShipping!!
        }
        _localShipping = materialIcon(name = "Outlined.LocalShipping") {
            materialPath {
                moveTo(20.0f, 8.0f)
                horizontalLineToRelative(-3.0f)
                lineTo(17.0f, 4.0f)
                lineTo(3.0f, 4.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, 0.9f, -2.0f, 2.0f)
                verticalLineToRelative(11.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.0f, 1.66f, 1.34f, 3.0f, 3.0f, 3.0f)
                reflectiveCurveToRelative(3.0f, -1.34f, 3.0f, -3.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-5.0f)
                lineToRelative(-3.0f, -4.0f)
                close()
                moveTo(19.5f, 9.5f)
                lineToRelative(1.96f, 2.5f)
                lineTo(17.0f, 12.0f)
                lineTo(17.0f, 9.5f)
                horizontalLineToRelative(2.5f)
                close()
                moveTo(6.0f, 18.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                close()
                moveTo(8.22f, 15.0f)
                curveToRelative(-0.55f, -0.61f, -1.33f, -1.0f, -2.22f, -1.0f)
                reflectiveCurveToRelative(-1.67f, 0.39f, -2.22f, 1.0f)
                lineTo(3.0f, 15.0f)
                lineTo(3.0f, 6.0f)
                horizontalLineToRelative(12.0f)
                verticalLineToRelative(9.0f)
                lineTo(8.22f, 15.0f)
                close()
                moveTo(18.0f, 18.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                reflectiveCurveToRelative(-0.45f, 1.0f, -1.0f, 1.0f)
                close()
            }
        }
        return _localShipping!!
    }

private var _localShipping: ImageVector? = null
