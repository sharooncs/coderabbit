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

public val Icons.Filled.Fastfood: ImageVector
    get() {
        if (_fastfood != null) {
            return _fastfood!!
        }
        _fastfood = materialIcon(name = "Filled.Fastfood") {
            materialPath {
                moveTo(18.06f, 22.99f)
                horizontalLineToRelative(1.66f)
                curveToRelative(0.84f, 0.0f, 1.53f, -0.64f, 1.63f, -1.46f)
                lineTo(23.0f, 5.05f)
                horizontalLineToRelative(-5.0f)
                lineTo(18.0f, 1.0f)
                horizontalLineToRelative(-1.97f)
                verticalLineToRelative(4.05f)
                horizontalLineToRelative(-4.97f)
                lineToRelative(0.3f, 2.34f)
                curveToRelative(1.71f, 0.47f, 3.31f, 1.32f, 4.27f, 2.26f)
                curveToRelative(1.44f, 1.42f, 2.43f, 2.89f, 2.43f, 5.29f)
                verticalLineToRelative(8.05f)
                close()
                moveTo(1.0f, 21.99f)
                lineTo(1.0f, 21.0f)
                horizontalLineToRelative(15.03f)
                verticalLineToRelative(0.99f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.01f, 1.0f)
                lineTo(2.01f, 22.99f)
                curveToRelative(-0.56f, 0.0f, -1.01f, -0.45f, -1.01f, -1.0f)
                close()
                moveTo(16.03f, 14.99f)
                curveToRelative(0.0f, -8.0f, -15.03f, -8.0f, -15.03f, 0.0f)
                horizontalLineToRelative(15.03f)
                close()
                moveTo(1.02f, 17.0f)
                horizontalLineToRelative(15.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(-15.0f)
                close()
            }
        }
        return _fastfood!!
    }

private var _fastfood: ImageVector? = null
