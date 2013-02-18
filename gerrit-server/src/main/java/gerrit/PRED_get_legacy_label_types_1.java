// Copyright (C) 2011 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package gerrit;

import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.rules.PrologEnvironment;
import com.google.gerrit.rules.StoredValues;
import com.google.gerrit.server.project.ProjectCache;
import com.google.gerrit.server.project.ProjectState;

import com.googlecode.prolog_cafe.lang.IntegerTerm;
import com.googlecode.prolog_cafe.lang.ListTerm;
import com.googlecode.prolog_cafe.lang.Operation;
import com.googlecode.prolog_cafe.lang.Predicate;
import com.googlecode.prolog_cafe.lang.Prolog;
import com.googlecode.prolog_cafe.lang.PrologException;
import com.googlecode.prolog_cafe.lang.StructureTerm;
import com.googlecode.prolog_cafe.lang.SymbolTerm;
import com.googlecode.prolog_cafe.lang.Term;

import java.util.List;

/**
 * Obtain a list of label types from the server configuration.
 * <p>
 * Unifies to a Prolog list of: {@code label_type(Label, Fun, Min, Max)}
 * where:
 * <ul>
 * <li>{@code Label} - the newer style label name</li>
 * <li>{@code Fun} - legacy function name</li>
 * <li>{@code Min, Max} - the smallest and largest configured values.</li>
 * </ul>
 */
class PRED_get_legacy_label_types_1 extends Predicate.P1 {
  PRED_get_legacy_label_types_1(Term a1, Operation n) {
    arg1 = a1;
    cont = n;
  }

  @Override
  public Operation exec(Prolog engine) throws PrologException {
    engine.setB0();
    Term a1 = arg1.dereference();

    PrologEnvironment env = (PrologEnvironment) engine.control;
    ProjectState state = env.getInjector().getInstance(ProjectCache.class)
        .get(StoredValues.CHANGE.get(engine).getDest().getParentKey());
    if (state == null) {
      return engine.fail();
    }
    List<LabelType> list = state.getLabelTypes().getLabelTypes();
    Term head = Prolog.Nil;
    for (int idx = list.size() - 1; 0 <= idx; idx--) {
      head = new ListTerm(export(list.get(idx)), head);
    }

    if (!a1.unify(head, engine.trail)) {
      return engine.fail();
    }
    return cont;
  }

  static final SymbolTerm symLabelType = SymbolTerm.intern(
      "label_type", 4);

  static Term export(LabelType type) {
    return new StructureTerm(symLabelType,
        SymbolTerm.intern(type.getName()),
        SymbolTerm.intern(type.getFunctionName()),
        new IntegerTerm(type.getMin().getValue()),
        new IntegerTerm(type.getMax().getValue()));
  }
}
