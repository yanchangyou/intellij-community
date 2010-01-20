package com.jetbrains.python.refactoring.classes.pushDown;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.refactoring.BaseRefactoringProcessor;
import com.intellij.refactoring.RefactoringBundle;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewDescriptor;
import com.intellij.util.Function;
import com.intellij.util.containers.ContainerUtil;
import com.jetbrains.python.psi.PyClass;
import com.jetbrains.python.psi.PyElement;
import com.jetbrains.python.psi.PyFunction;
import com.jetbrains.python.psi.impl.PyPsiUtils;
import com.jetbrains.python.psi.search.PyClassInheritorsSearch;
import com.jetbrains.python.refactoring.classes.PyClassRefactoringUtil;
import com.jetbrains.python.refactoring.classes.PyMemberInfo;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * @author Dennis.Ushakov
 */
public class PyPushDownProcessor extends BaseRefactoringProcessor {
  private static final Logger LOG = Logger.getInstance(PyPushDownProcessor.class.getName());

  private PyClass myClass;
  private Collection<PyMemberInfo> mySelectedMemberInfos;

  protected PyPushDownProcessor(Project project, PyClass clazz, Collection<PyMemberInfo> selectedMemberInfos) {
    super(project);
    myClass = clazz;
    mySelectedMemberInfos = selectedMemberInfos;
  }

  @Override
  protected UsageViewDescriptor createUsageViewDescriptor(UsageInfo[] usages) {
    return new PyPushDownUsageViewDescriptor(myClass);
  }

  @NotNull
  @Override
  protected UsageInfo[] findUsages() {
    final Collection<PyClass> subClasses = PyClassInheritorsSearch.search(myClass, false).findAll();
    final UsageInfo[] result = new UsageInfo[subClasses.size()];
    ContainerUtil.map2Array(subClasses, result, new Function<PyClass, UsageInfo>() {
      public UsageInfo fun(PyClass pyClass) {
        return new UsageInfo(pyClass);
      }
    });
    return result;
  }

  @Override
  protected void refreshElements(PsiElement[] elements) {
    if (elements.length == 1 && elements[0] instanceof PyClass) {
      myClass = (PyClass)elements[0];
    }
  }

  @Override
  protected void performRefactoring(UsageInfo[] usages) {
    final Set<String> superClasses = new HashSet<String>();
    final List<PyFunction> methods = new ArrayList<PyFunction>();
    for (PyMemberInfo member : mySelectedMemberInfos) {
      final PyElement element = member.getMember();
      if (element instanceof PyFunction) methods.add((PyFunction)element);
      else if (element instanceof PyClass) superClasses.add(element.getName());
      else LOG.error("unmatched member class " + element.getClass());
    }

    final PsiElement[] elements = methods.toArray(new PsiElement[methods.size()]);
    if (methods.size() != 0) {
      PyPsiUtils.removeElements(elements);
    }

    final List<PsiElement> superClassesElements = PyClassRefactoringUtil.removeAndGetSuperClasses(myClass, superClasses);

    for (UsageInfo usage : usages) {
      final PyClass targetClass = (PyClass)usage.getElement();
      PyClassRefactoringUtil.addMethods(targetClass, elements);
      PyClassRefactoringUtil.addSuperclasses(myClass.getProject(), targetClass, superClassesElements, superClasses);
    }
  }

  @Override
  protected boolean preprocessUsages(Ref<UsageInfo[]> ref) {
    final UsageInfo[] usages = ref.get();
    final PyPushDownConflicts conflicts = new PyPushDownConflicts(myClass, mySelectedMemberInfos);
    conflicts.checkSourceClassConflicts();

    if (usages.length == 0) {
      final String message = RefactoringBundle.message("class.0.does.not.have.inheritors", myClass.getName() + "\n" + RefactoringBundle.message("push.down.will.delete.members"));
      final int answer = Messages.showYesNoDialog(message, PyPushDownHandler.REFACTORING_NAME, Messages.getWarningIcon());
      if (answer != 0) {
        return false;
      }
    }

    for (UsageInfo usage : usages) {
       conflicts.checkTargetClassConflicts(usage.getElement());
    }
    return showConflicts(conflicts.getConflicts());
  }

  @Override
  protected String getCommandName() {
    return PyPushDownHandler.REFACTORING_NAME;
  }
}