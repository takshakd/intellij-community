/*
 * Copyright 2000-2015 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 30-Jan-2008
 */
package com.intellij.codeInsight;

import com.intellij.codeInsight.completion.CompletionUtil;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.ide.util.EditSourceUtil;
import com.intellij.lang.Language;
import com.intellij.lang.LanguageExtension;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.actions.EditorActionUtil;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.pom.Navigatable;
import com.intellij.pom.PomDeclarationSearcher;
import com.intellij.pom.PomTarget;
import com.intellij.pom.PsiDeclaredTarget;
import com.intellij.pom.references.PomService;
import com.intellij.psi.*;
import com.intellij.psi.search.PsiSearchHelper;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.Consumer;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class TargetElementUtilBase {
  public static final int REFERENCED_ELEMENT_ACCEPTED = 0x01;
  public static final int ELEMENT_NAME_ACCEPTED = 0x02;
  public static final int LOOKUP_ITEM_ACCEPTED = 0x08;

  public static TargetElementUtilBase getInstance() {
    return ServiceManager.getService(TargetElementUtilBase.class);
  }

  public int getAllAccepted() {
    int result = REFERENCED_ELEMENT_ACCEPTED | ELEMENT_NAME_ACCEPTED | LOOKUP_ITEM_ACCEPTED;
    for (TargetElementUtilExtender each : Extensions.getExtensions(TargetElementUtilExtender.EP_NAME)) {
      result |= each.getAdditionalAccepted();
    }
    return result;
  }

  /**
   * Accepts THIS or SUPER but not NEW_AS_CONSTRUCTOR.
   */
  public int getDefinitionSearchFlags() {
    int result = getAllAccepted();
    for (TargetElementUtilExtender each : Extensions.getExtensions(TargetElementUtilExtender.EP_NAME)) {
      result |= each.getAdditionalDefinitionSearchFlags();
    }
    return result;
  }

  /**
   * Accepts NEW_AS_CONSTRUCTOR but not THIS or SUPER.
   */
  public int getReferenceSearchFlags() {
    int result = getAllAccepted();
    for (TargetElementUtilExtender each : Extensions.getExtensions(TargetElementUtilExtender.EP_NAME)) {
      result |= each.getAdditionalReferenceSearchFlags();
    }
    return result;
  }

  @Nullable
  public static PsiReference findReference(Editor editor) {
    PsiReference result = findReference(editor, editor.getCaretModel().getOffset());
    if (result == null) {
      final Integer offset = editor.getUserData(EditorActionUtil.EXPECTED_CARET_OFFSET);
      if (offset != null) {
        result = findReference(editor, offset);
      }
    }
    return result;
  }

  @Nullable
  public static PsiReference findReference(@NotNull Editor editor, int offset) {
    Project project = editor.getProject();
    if (project == null) return null;

    Document document = editor.getDocument();
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
    if (file == null) return null;
    if (ApplicationManager.getApplication().isDispatchThread()) {
      PsiDocumentManager.getInstance(project).commitAllDocuments();
    }

    offset = adjustOffset(file, document, offset);

    if (file instanceof PsiCompiledFile) {
      return ((PsiCompiledFile) file).getDecompiledPsiFile().findReferenceAt(offset);
    }

    return file.findReferenceAt(offset);
  }

  /**
   * @deprecated adjust offset with PsiElement should be used instead to provide correct checking for identifier part
   */
  public static int adjustOffset(Document document, final int offset) {
    return adjustOffset(null, document, offset);
  }

  public static int adjustOffset(@Nullable PsiFile file, Document document, final int offset) {
    CharSequence text = document.getCharsSequence();
    int correctedOffset = offset;
    int textLength = document.getTextLength();
    if (offset >= textLength) {
      correctedOffset = textLength - 1;
    }
    else if (!isIdentifierPart(file, text, offset)) {
      correctedOffset--;
    }
    if (correctedOffset < 0 || !isIdentifierPart(file, text, correctedOffset)) return offset;
    return correctedOffset;
  }

  private static boolean isIdentifierPart(@Nullable PsiFile file, CharSequence text, int offset) {
    if (file != null) {
      TargetElementEvaluatorEx evaluator = getInstance().getElementEvaluatorsEx(file.getLanguage());
      if (evaluator != null && evaluator.isIdentifierPart(file, text, offset)) return true;
    }
    return Character.isJavaIdentifierPart(text.charAt(offset));
  }

  public static boolean inVirtualSpace(@NotNull Editor editor, int offset) {
    if (offset == editor.getCaretModel().getOffset()) {
      return EditorUtil.inVirtualSpace(editor, editor.getCaretModel().getLogicalPosition());
    }

    return false;
  }

  @Nullable
  public static PsiElement findTargetElement(Editor editor, int flags) {
    ApplicationManager.getApplication().assertIsDispatchThread();

    final PsiElement result = getInstance().findTargetElement(editor, flags, editor.getCaretModel().getOffset());
    if (result != null) return result;

    final Integer offset = editor.getUserData(EditorActionUtil.EXPECTED_CARET_OFFSET);
    if (offset != null) {
      return getInstance().findTargetElement(editor, flags, offset);
    }
    return null;
  }

  @Nullable
  public PsiElement findTargetElement(@NotNull Editor editor, int flags, int offset) {
    PsiElement result = doFindTargetElement(editor, flags, offset);
    TargetElementEvaluatorEx2 evaluator = result != null ? getElementEvaluatorsEx2(result.getLanguage()) : null;
    if (evaluator != null) {
      result = evaluator.adjustTargetElement(editor, offset, flags, result);
    }
    return result;
  }

  @Nullable
  private PsiElement doFindTargetElement(@NotNull Editor editor, int flags, int offset) {
    Project project = editor.getProject();
    if (project == null) return null;

    if ((flags & LOOKUP_ITEM_ACCEPTED) != 0) {
      PsiElement element = getTargetElementFromLookup(project);
      if (element != null) {
        return element;
      }
    }

    Document document = editor.getDocument();
    if (ApplicationManager.getApplication().isDispatchThread()) {
      PsiDocumentManager.getInstance(project).commitAllDocuments();
    }
    PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(document);
    if (file == null) return null;

    offset = adjustOffset(file, document, offset);

    PsiElement element = file.findElementAt(offset);
    if ((flags & REFERENCED_ELEMENT_ACCEPTED) != 0) {
      final PsiElement referenceOrReferencedElement = getReferenceOrReferencedElement(file, editor, flags, offset);
      //if (referenceOrReferencedElement == null) {
      //  return getReferenceOrReferencedElement(file, editor, flags, offset);
      //}
      if (isAcceptableReferencedElement(element, referenceOrReferencedElement)) {
        return referenceOrReferencedElement;
      }
    }

    if (element == null) return null;

    if ((flags & ELEMENT_NAME_ACCEPTED) != 0) {
      if (element instanceof PsiNamedElement) return element;
      return getNamedElement(element, offset - element.getTextRange().getStartOffset());
    }
    return null;
  }

  @Nullable
  private static PsiElement getTargetElementFromLookup(Project project) {
    Lookup activeLookup = LookupManager.getInstance(project).getActiveLookup();
    if (activeLookup != null) {
      LookupElement item = activeLookup.getCurrentItem();
      if (item != null && item.isValid()) {
        final PsiElement psi = CompletionUtil.getTargetElement(item);
        if (psi != null && psi.isValid()) {
          return psi;
        }
      }
    }
    return null;
  }

  protected boolean isAcceptableReferencedElement(@Nullable PsiElement element, @Nullable PsiElement referenceOrReferencedElement) {
    if (referenceOrReferencedElement == null || !referenceOrReferencedElement.isValid()) return false;
    
    TargetElementEvaluatorEx2 evaluator = element != null ? getElementEvaluatorsEx2(element.getLanguage()) : null;
    if (evaluator != null) {
      TargetElementEvaluatorEx2.Answer answer = evaluator.isAcceptableReferencedElement(element, referenceOrReferencedElement);
      if (answer == TargetElementEvaluatorEx2.Answer.YES) return true;
      if (answer == TargetElementEvaluatorEx2.Answer.NO) return false;
    }

    return true;
  }

  @Nullable
  public PsiElement adjustElement(final Editor editor, final int flags, @Nullable PsiElement element, @Nullable PsiElement contextElement) {
    PsiElement langElement = element == null ? contextElement : element;
    TargetElementEvaluatorEx2 evaluator = langElement != null ? getElementEvaluatorsEx2(langElement.getLanguage()) : null;
    if (evaluator != null) {
      element = evaluator.adjustElement(editor, flags, element, contextElement);
    }
    return element;
  }

  @Nullable
  public PsiElement adjustReference(@NotNull PsiReference ref) {
    PsiElement element = ref.getElement();
    TargetElementEvaluatorEx2 evaluator = element != null ? getElementEvaluatorsEx2(element.getLanguage()) : null;
    return evaluator != null ? evaluator.adjustReference(ref) : null;
  }

  @Nullable
  public PsiElement getNamedElement(@Nullable final PsiElement element, final int offsetInElement) {
    if (element == null) return null;

    final List<PomTarget> targets = ContainerUtil.newArrayList();
    final Consumer<PomTarget> consumer = new Consumer<PomTarget>() {
      @Override
      public void consume(PomTarget target) {
        if (target instanceof PsiDeclaredTarget) {
          final PsiDeclaredTarget declaredTarget = (PsiDeclaredTarget)target;
          final PsiElement navigationElement = declaredTarget.getNavigationElement();
          final TextRange range = declaredTarget.getNameIdentifierRange();
          if (range != null && !range.shiftRight(navigationElement.getTextRange().getStartOffset())
            .contains(element.getTextRange().getStartOffset() + offsetInElement)) {
            return;
          }
        }
        targets.add(target);
      }
    };

    PsiElement parent = element;

    int offset = offsetInElement;
    while (parent != null) {
      for (PomDeclarationSearcher searcher : PomDeclarationSearcher.EP_NAME.getExtensions()) {
        searcher.findDeclarationsAt(parent, offset, consumer);
        if (!targets.isEmpty()) {
          final PomTarget target = targets.get(0);
          return target == null ? null : PomService.convertToPsi(element.getProject(), target);
        }
      }
      offset += parent.getStartOffsetInParent();
      parent = parent.getParent();
    }

    return getNamedElement(element);
  }


  @Nullable
  private PsiElement getNamedElement(@Nullable final PsiElement element) {
    if (element == null) return null;
    
    TargetElementEvaluatorEx2 evaluator = getElementEvaluatorsEx2(element.getLanguage());
    if (evaluator != null) {
      PsiElement result = evaluator.getNamedElement(element);
      if (result != null) return result;
    }
    
    PsiElement parent;
    if ((parent = PsiTreeUtil.getParentOfType(element, PsiNamedElement.class, false)) != null) {
      // A bit hacky depends on navigation offset correctly overridden
      if (parent.getTextOffset() == element.getTextRange().getStartOffset()) {
        if (evaluator == null || evaluator.isAcceptableNamedParent(parent)) {
          return parent;
        }
      }
    }

    return null;
  }

  @Nullable
  private PsiElement getReferenceOrReferencedElement(PsiFile file, Editor editor, int flags, int offset) {
    PsiElement result = doGetReferenceOrReferencedElement(file, editor, flags, offset);
    PsiElement languageElement = file.findElementAt(offset);
    Language language = languageElement != null ? languageElement.getLanguage() : file.getLanguage();
    TargetElementEvaluatorEx2 evaluator = getElementEvaluatorsEx2(language);
    if (evaluator != null) {
      result = evaluator.adjustReferenceOrReferencedElement(file, editor, offset, flags, result);
    }
    return result;
  }

  @Nullable
  private PsiElement doGetReferenceOrReferencedElement(PsiFile file, Editor editor, int flags, int offset) {
    PsiReference ref = findReference(editor, offset);
    if (ref == null) return null;

    final Language language = ref.getElement().getLanguage();
    TargetElementEvaluator evaluator = targetElementEvaluator.forLanguage(language);
    if (evaluator != null) {
      final PsiElement element = evaluator.getElementByReference(ref, flags);
      if (element != null) return element;
    }

    PsiManager manager = file.getManager();
    PsiElement refElement = ref.resolve();
    if (refElement == null) {
      if (ApplicationManager.getApplication().isDispatchThread()) {
        DaemonCodeAnalyzer.getInstance(manager.getProject()).updateVisibleHighlighters(editor);
      }
      return null;
    }
    else {
      return refElement;
    }
  }

  @NotNull
  public Collection<PsiElement> getTargetCandidates(@NotNull PsiReference reference) {
    PsiElement refElement = reference.getElement();
    TargetElementEvaluatorEx2 evaluator = refElement != null ? getElementEvaluatorsEx2(refElement.getLanguage()) : null;
    if (evaluator != null) {
      Collection<PsiElement> candidates = evaluator.getTargetCandidates(reference);
      if (candidates != null) return candidates;
    }
    
    if (reference instanceof PsiPolyVariantReference) {
      final ResolveResult[] results = ((PsiPolyVariantReference)reference).multiResolve(false);
      List<PsiElement> navigatableResults = new ArrayList<PsiElement>(results.length);

      for(ResolveResult r:results) {
        PsiElement element = r.getElement();
        if (EditSourceUtil.canNavigate(element) || element instanceof Navigatable && ((Navigatable)element).canNavigateToSource()) {
          navigatableResults.add(element);
        }
      }

      return navigatableResults;
    }
    PsiElement resolved = reference.resolve();
    if (resolved instanceof NavigationItem) {
      return Collections.singleton(resolved);
    }
    return Collections.emptyList();
  }

  public PsiElement getGotoDeclarationTarget(final PsiElement element, final PsiElement navElement) {
    TargetElementEvaluatorEx2 evaluator = element != null ? getElementEvaluatorsEx2(element.getLanguage()) : null;
    if (evaluator != null) {
      PsiElement result = evaluator.getGotoDeclarationTarget(element, navElement);
      if (result != null) return result;
    }
    return navElement;
  }

  public boolean includeSelfInGotoImplementation(@NotNull final PsiElement element) {
    TargetElementEvaluator evaluator = targetElementEvaluator.forLanguage(element.getLanguage());
    return evaluator == null || evaluator.includeSelfInGotoImplementation(element);
  }

  public boolean acceptImplementationForReference(@Nullable PsiReference reference, @Nullable PsiElement element) {
    TargetElementEvaluatorEx2 evaluator = element != null ? getElementEvaluatorsEx2(element.getLanguage()) : null;
    return evaluator == null || evaluator.acceptImplementationForReference(reference, element);
  }

  @NotNull
  public SearchScope getSearchScope(Editor editor, @NotNull PsiElement element) {
    TargetElementEvaluatorEx2 evaluator = getElementEvaluatorsEx2(element.getLanguage());
    SearchScope result = evaluator != null ? evaluator.getSearchScope(editor, element) : null;
    return result != null ? result : PsiSearchHelper.SERVICE.getInstance(element.getProject()).getUseScope(element);
  }

  protected final LanguageExtension<TargetElementEvaluator> targetElementEvaluator = new LanguageExtension<TargetElementEvaluator>("com.intellij.targetElementEvaluator");
  @Nullable
  private TargetElementEvaluatorEx getElementEvaluatorsEx(@NotNull Language language) {
    TargetElementEvaluator result = targetElementEvaluator.forLanguage(language);
    return result instanceof TargetElementEvaluatorEx ? (TargetElementEvaluatorEx)result : null;
  }
  @Nullable
  private TargetElementEvaluatorEx2 getElementEvaluatorsEx2(@NotNull Language language) {
    TargetElementEvaluator result = targetElementEvaluator.forLanguage(language);
    return result instanceof TargetElementEvaluatorEx2 ? (TargetElementEvaluatorEx2)result : null;
  }
}
