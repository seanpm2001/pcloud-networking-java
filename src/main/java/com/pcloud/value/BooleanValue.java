/*
 * Copyright (c) 2016 Georgi Neykov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pcloud.value;

public class BooleanValue extends PrimitiveValue {
    protected boolean value;

    public BooleanValue(boolean value) {
        this.value = value;
    }

    public final boolean getValue() {
        return value;
    }

    @Override
    public final boolean isBoolean() {
        return true;
    }

    @Override
    public String toString() {
        return String.valueOf(value);
    }
}
