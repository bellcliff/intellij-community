/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.inspections;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.codeInspection.ui.MultipleCheckboxOptionsPanel;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiReference;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.controlflow.ScopeOwner;
import com.jetbrains.python.codeInsight.dataflow.scope.ScopeUtil;
import com.jetbrains.python.codeInsight.stdlib.PyNamedTupleType;
import com.jetbrains.python.inspections.quickfix.PyAddPropertyForFieldQuickFix;
import com.jetbrains.python.inspections.quickfix.PyMakePublicQuickFix;
import com.jetbrains.python.inspections.quickfix.PyRenameElementQuickFix;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyExpression;
import com.jetbrains.python.psi.PyReferenceExpression;
import com.jetbrains.python.psi.PyTargetExpression;
import com.jetbrains.python.psi.types.PyModuleType;
import com.jetbrains.python.psi.types.PyType;
import com.jetbrains.python.refactoring.PyRefactoringUtil;
import com.jetbrains.python.testing.pytest.PyTestUtil;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * User: ktisha
 *
 * Inspection to detect situations, where
 * protected member (i.e. class member with a name beginning with an underscore)
 * is access outside the class or a descendant of the class where it's defined.
 */
public class PyProtectedMemberInspection extends PyInspection {
  public boolean ignoreTestFunctions = true;

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return PyBundle.message("INSP.NAME.protected.member.access");
  }

  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder,
                                        boolean isOnTheFly,
                                        @NotNull LocalInspectionToolSession session) {
    return new Visitor(holder, session);
  }


  private class Visitor extends PyInspectionVisitor {
    public Visitor(@Nullable ProblemsHolder holder, @NotNull LocalInspectionToolSession session) {
      super(holder, session);
    }

    @Override
    public void visitPyReferenceExpression(PyReferenceExpression node) {
      final PyExpression qualifier = node.getQualifier();
      if (qualifier == null || PyNames.CANONICAL_SELF.equals(qualifier.getText())) return;
      if (myTypeEvalContext.getType(qualifier) instanceof PyNamedTupleType) return;
      final String name = node.getName();
      final List<LocalQuickFix> quickFixes = new ArrayList<LocalQuickFix>();
      quickFixes.add(new PyRenameElementQuickFix());

      if (name != null && name.startsWith("_") && !name.startsWith("__") && !name.endsWith("__")) {
        final PsiReference reference = node.getReference(getResolveContext());
        if (reference == null) return;
        final PsiElement resolvedExpression = reference.resolve();
        final PyClass resolvedClass = getClassOwner(resolvedExpression);
        if (resolvedExpression instanceof PyTargetExpression) {
          final String newName = StringUtil.trimLeading(name, '_');
          if (resolvedClass != null) {
            final String qFixName = resolvedClass.getProperties().containsKey(newName) ?
                              PyBundle.message("QFIX.use.property") : PyBundle.message("QFIX.add.property");
            quickFixes.add(new PyAddPropertyForFieldQuickFix(qFixName));

            final Collection<String> usedNames = PyRefactoringUtil.collectUsedNames(resolvedClass);
            if (!usedNames.contains(newName)) {
              quickFixes.add(new PyMakePublicQuickFix());
            }
          }
        }

        final PyClass parentClass = getClassOwner(node);
        if (parentClass != null) {
          if (PyTestUtil.isPyTestClass(parentClass) && ignoreTestFunctions) return;
          
          if (parentClass.isSubclass(resolvedClass))
            return;

          PyClass outerClass = getClassOwner(parentClass);
          while (outerClass != null) {
            if (outerClass.isSubclass(resolvedClass))
              return;

            outerClass = getClassOwner(outerClass);
          }
        }
        final PyType type = myTypeEvalContext.getType(qualifier);
        final String bundleKey = type instanceof PyModuleType ? "INSP.protected.member.$0.access.module" : "INSP.protected.member.$0.access";
        registerProblem(node, PyBundle.message(bundleKey, name), ProblemHighlightType.GENERIC_ERROR_OR_WARNING,  null, quickFixes.toArray(new LocalQuickFix[quickFixes.size()-1]));
      }
    }

    @Nullable
    private PyClass getClassOwner(@Nullable PsiElement element) {
      for (ScopeOwner owner = ScopeUtil.getScopeOwner(element); owner != null; owner = ScopeUtil.getScopeOwner(owner)) {
        if (owner instanceof PyClass) {
          return (PyClass)owner;
        }
      }
      return null;
    }
  }

  @Nullable
  @Override
  public JComponent createOptionsPanel() {
    MultipleCheckboxOptionsPanel panel = new MultipleCheckboxOptionsPanel(this);
    panel.addCheckbox("Ignore test functions", "ignoreTestFunctions");
    return panel;
  }
}
