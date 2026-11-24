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

public val Icons.Filled.ShoppingBasket: ImageVector
    get() {
        if (_shoppingBasket != null) {
            return _shoppingBasket!!
        }
        _shoppingBasket = materialIcon(name = "Filled.ShoppingBasket") {
            materialPath {
                moveTo(17.21f, 9.0f)
                lineToRelative(-4.38f, -6.56f)
                curveToRelative(-0.19f, -0.28f, -0.51f, -0.42f, -0.83f, -0.42f)
                curveToRelative(-0.32f, 0.0f, -0.64f, 0.14f, -0.83f, 0.43f)
                lineTo(6.79f, 9.0f)
                lineTo(2.0f, 9.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                curveToRelative(0.0f, 0.09f, 0.01f, 0.18f, 0.04f, 0.27f)
                lineToRelative(2.54f, 9.27f)
                curveToRelative(0.23f, 0.84f, 1.0f, 1.46f, 1.92f, 1.46f)
                horizontalLineToRelative(13.0f)
                curveToRelative(0.92f, 0.0f, 1.69f, -0.62f, 1.93f, -1.46f)
                lineToRelative(2.54f, -9.27f)
                lineTo(23.0f, 10.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineToRelative(-4.79f)
                close()
                moveTo(9.0f, 9.0f)
                lineToRelative(3.0f, -4.4f)
                lineTo(15.0f, 9.0f)
                lineTo(9.0f, 9.0f)
                close()
                moveTo(12.0f, 17.0f)
                curveToRelative(-1.1f, 0.0f, -2.0f, -0.9f, -2.0f, -2.0f)
                reflectiveCurveToRelative(0.9f, -2.0f, 2.0f, -2.0f)
                reflectiveCurveToRelative(2.0f, 0.9f, 2.0f, 2.0f)
                reflectiveCurveToRelative(-0.9f, 2.0f, -2.0f, 2.0f)
                close()
            }
        }
        return _shoppingBasket!!
    }

private var _shoppingBasket: ImageVector? = null
