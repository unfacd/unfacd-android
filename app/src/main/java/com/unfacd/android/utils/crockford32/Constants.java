/**
 * Licensed to Media Science International (MSI) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. MSI
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.unfacd.android.utils.crockford32;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Codec implementation data.
 */
final class Constants {

  public static final String ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  public static final Collection<CodeMap> EQUIVALENTS;

  public static final int RADIX;

  static {
    RADIX = ALPHABET.length();

    final ArrayList<CodeMap> equivalents = new ArrayList<>();
    equivalents.add(new CodeMap('o', '0'));
    equivalents.add(new CodeMap('O', '0'));
    equivalents.add(new CodeMap('i', '1'));
    equivalents.add(new CodeMap('I', '1'));
    equivalents.add(new CodeMap('l', '1'));
    equivalents.add(new CodeMap('L', '1'));
    EQUIVALENTS = Collections.unmodifiableCollection(equivalents);

  }

  /**
   * No instances.
   */
  private Constants() {
    throw new AssertionError("no instance allowed");
  }

}