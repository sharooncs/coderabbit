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

public val Icons.Filled.DryCleaning: ImageVector
    get() {
        if (_dryCleaning != null) {
            return _dryCleaning!!
        }
        _dryCleaning = materialIcon(name = "Filled.DryCleaning") {
            materialPath {
                moveTo(19.56f, 11.36f)
                lineTo(13.0f, 8.44f)
                verticalLineTo(7.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                lineToRelative(0.0f, 0.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                reflectiveCurveToRelative(0.45f, -1.0f, 1.0f, -1.0f)
                reflectiveCurveToRelative(1.0f, 0.45f, 1.0f, 1.0f)
                horizontalLineToRelative(2.0f)
                curveToRelative(0.0f, -1.84f, -1.66f, -3.3f, -3.56f, -2.95f)
                curveTo(10.26f, 2.27f, 9.29f, 3.22f, 9.06f, 4.4f)
                curveTo(8.76f, 5.96f, 9.66f, 7.34f, 11.0f, 7.82f)
                verticalLineToRelative(0.63f)
                lineToRelative(-6.56f, 2.92f)
                curveTo(3.56f, 11.75f, 3.0f, 12.62f, 3.0f, 13.57f)
                verticalLineToRelative(0.01f)
                curveTo(3.0f, 14.92f, 4.08f, 16.0f, 5.42f, 16.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(10.0f)
                verticalLineToRelative(-6.0f)
                horizontalLineToRelative(1.58f)
                curveToRelative(1.34f, 0.0f, 2.42f, -1.08f, 2.42f, -2.42f)
                verticalLineToRelative(-0.01f)
                curveTo(21.0f, 12.62f, 20.44f, 11.75f, 19.56f, 11.36f)
                close()
                moveTo(18.58f, 14.0f)
                horizontalLineTo(17.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineTo(7.0f)
                verticalLineToRelative(1.0f)
                horizontalLineTo(5.42f)
                curveTo(5.19f, 14.0f, 5.0f, 13.81f, 5.0f, 13.57f)
                curveToRelative(0.0f, -0.17f, 0.1f, -0.32f, 0.25f, -0.38f)
                lineToRelative(6.75f, -3.0f)
                lineToRelative(6.75f, 3.0f)
                curveTo(18.9f, 13.26f, 19.0f, 13.41f, 19.0f, 13.58f)
                curveTo(19.0f, 13.81f, 18.81f, 14.0f, 18.58f, 14.0f)
                close()
            }
        }
        return _dryCleaning!!
    }

private var _dryCleaning: ImageVector? = null
