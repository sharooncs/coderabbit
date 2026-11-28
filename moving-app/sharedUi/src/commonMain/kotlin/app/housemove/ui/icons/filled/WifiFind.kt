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

public val Icons.Filled.WifiFind: ImageVector
    get() {
        if (_wifiFind != null) {
            return _wifiFind!!
        }
        _wifiFind = materialIcon(name = "Filled.WifiFind") {
            materialPath {
                moveTo(11.0f, 14.0f)
                curveToRelative(0.0f, -3.36f, 2.64f, -6.0f, 6.0f, -6.0f)
                curveToRelative(2.2f, 0.0f, 4.08f, 1.13f, 5.13f, 2.86f)
                lineTo(24.0f, 8.98f)
                curveTo(20.93f, 5.9f, 16.69f, 4.0f, 12.0f, 4.0f)
                curveTo(7.31f, 4.0f, 3.07f, 5.9f, 0.0f, 8.98f)
                lineTo(12.0f, 21.0f)
                lineToRelative(1.86f, -1.87f)
                curveTo(12.14f, 18.09f, 11.0f, 16.2f, 11.0f, 14.0f)
                close()
            }
            materialPath {
                moveTo(21.0f, 14.0f)
                curveToRelative(0.0f, -2.24f, -1.76f, -4.0f, -4.0f, -4.0f)
                reflectiveCurveToRelative(-4.0f, 1.76f, -4.0f, 4.0f)
                curveToRelative(0.0f, 2.24f, 1.76f, 4.0f, 4.0f, 4.0f)
                curveToRelative(0.75f, 0.0f, 1.44f, -0.21f, 2.03f, -0.56f)
                lineTo(21.59f, 20.0f)
                lineTo(23.0f, 18.59f)
                lineToRelative(-2.56f, -2.56f)
                curveTo(20.79f, 15.44f, 21.0f, 14.75f, 21.0f, 14.0f)
                close()
                moveTo(15.0f, 14.0f)
                curveToRelative(0.0f, -1.12f, 0.88f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.88f, 2.0f, 2.0f)
                curveToRelative(0.0f, 1.12f, -0.88f, 2.0f, -2.0f, 2.0f)
                reflectiveCurveTo(15.0f, 15.12f, 15.0f, 14.0f)
                close()
            }
        }
        return _wifiFind!!
    }

private var _wifiFind: ImageVector? = null
