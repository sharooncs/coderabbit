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

public val Icons.Filled.Roofing: ImageVector
    get() {
        if (_roofing != null) {
            return _roofing!!
        }
        _roofing = materialIcon(name = "Filled.Roofing") {
            materialPath {
                moveTo(13.0f, 18.0f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(-2.0f)
                horizontalLineToRelative(2.0f)
                verticalLineTo(18.0f)
                close()
                moveTo(15.0f, 14.0f)
                horizontalLineTo(9.0f)
                verticalLineToRelative(6.0f)
                horizontalLineToRelative(6.0f)
                verticalLineTo(14.0f)
                lineTo(15.0f, 14.0f)
                close()
                moveTo(19.0f, 9.3f)
                lineTo(19.0f, 9.3f)
                verticalLineTo(4.0f)
                horizontalLineToRelative(-3.0f)
                verticalLineToRelative(2.6f)
                verticalLineToRelative(0.0f)
                lineTo(12.0f, 3.0f)
                lineTo(2.0f, 12.0f)
                horizontalLineToRelative(3.0f)
                lineToRelative(7.0f, -6.31f)
                lineTo(19.0f, 12.0f)
                horizontalLineToRelative(3.0f)
                lineTo(19.0f, 9.3f)
                close()
            }
        }
        return _roofing!!
    }

private var _roofing: ImageVector? = null
