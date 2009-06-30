package com.intellij.codeInsight.lookup.impl;

import com.intellij.codeInsight.completion.JavaCompletionUtil;
import com.intellij.codeInsight.lookup.DefaultLookupItemRenderer;
import com.intellij.codeInsight.lookup.LookupItem;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CodeStyleSettingsManager;
import com.intellij.psi.impl.beanProperties.BeanPropertyElement;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiUtilBase;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author yole
 */
public class JavaElementLookupRenderer implements ElementLookupRenderer {
  public boolean handlesItem(final Object element) {
    return element instanceof PsiClass || element instanceof PsiMember || element instanceof PsiVariable ||
           element instanceof PsiType || element instanceof PsiKeyword || element instanceof PsiExpression ||
           element instanceof PsiTypeElement || element instanceof BeanPropertyElement;
  }

  public void renderElement(final LookupItem item, final Object element, final LookupElementPresentationEx presentation) {
    presentation.setIcon(DefaultLookupItemRenderer.getRawIcon(item, presentation.isReal()));

    final boolean bold = item.getAttribute(LookupItem.HIGHLIGHTED_ATTR) != null;
    final boolean grayed = item.getAttribute(LookupItem.TAIL_TEXT_SMALL_ATTR) != null;

    boolean strikeout = isToStrikeout(item);
    presentation.setItemText(getName(element, item), strikeout, bold);

    final String tailText = getTailText(element, item);
    presentation.setTailText(tailText != null ? tailText : "", grayed, false, strikeout);

    final String typeText = getTypeText(element, item);
    presentation.setTypeText(typeText != null ? typeText : "");
  }

  private static String getName(final Object o, final LookupItem<?> item) {
    final String presentableText = item.getPresentableText();
    if (presentableText != null) {
      return presentableText;
    }

    String name = "";
    if (o instanceof PsiElement) {
      final PsiElement element = (PsiElement)o;
      if (element.isValid()) {
        name = PsiUtilBase.getName(element);

        if (element instanceof PsiClass) {
          PsiSubstitutor substitutor = (PsiSubstitutor)item.getAttribute(LookupItem.SUBSTITUTOR);
          if (substitutor != null && !substitutor.isValid()) {
            PsiType type = (PsiType)item.getAttribute(LookupItem.TYPE);
            if (type != null) {
              name = type.getPresentableText();
            }
          }
          else {
            name = formatTypeName((PsiClass)element, substitutor);
          }
        }
        else if (element instanceof PsiKeyword || element instanceof PsiExpression || element instanceof PsiTypeElement) {
          name = element.getText();
        }
      }
    }
    else if (o instanceof PsiArrayType) {
      name = ((PsiArrayType)o).getDeepComponentType().getPresentableText();
    }
    else if (o instanceof PsiType) {
      name = ((PsiType)o).getPresentableText();
    }

    if (item.getAttribute(LookupItem.FORCE_QUALIFY) != null) {
      if (o instanceof PsiMember && ((PsiMember)o).getContainingClass() != null) {
        name = ((PsiMember)o).getContainingClass().getName() + "." + name;
      }
    }

    return StringUtil.notNullize(name);
  }

  @Nullable
  private static String getTailText(final Object o, final LookupItem item) {
    String text = null;
    if (o instanceof PsiElement) {
      final PsiElement element = (PsiElement)o;
      if (element.isValid() && element instanceof PsiMethod){
        PsiMethod method = (PsiMethod)element;
        final PsiSubstitutor substitutor = (PsiSubstitutor) item.getAttribute(LookupItem.SUBSTITUTOR);
        text = PsiFormatUtil.formatMethod(method,
                                          substitutor != null ? substitutor : PsiSubstitutor.EMPTY,
                                          PsiFormatUtil.SHOW_PARAMETERS,
                                          PsiFormatUtil.SHOW_NAME | PsiFormatUtil.SHOW_TYPE);
      }
    }

    String tailText = (String)item.getAttribute(LookupItem.TAIL_TEXT_ATTR);
    if (tailText != null){
      if (text == null){
        text = tailText;
      }
      else{
        text += tailText;
      }
    }
    if(item.getAttribute(LookupItem.INDICATE_ANONYMOUS) != null){
      if(o instanceof PsiClass){
        final PsiClass psiClass = (PsiClass) o;
        if(psiClass.isInterface() || psiClass.hasModifierProperty(PsiModifier.ABSTRACT)){
          text += "{...}";
        }
      }
    }
    return text;
  }

  @Nullable
  private static String getTypeText(final Object o, final LookupItem item) {
    String text = null;
    PsiType typeAttr = (PsiType)item.getAttribute(LookupItem.TYPE_ATTR);
    if (typeAttr != null){
      text = typeAttr.getPresentableText();
    }
    else {
      if (o instanceof PsiElement) {
        final PsiElement element = (PsiElement)o;
        if (element.isValid()) {
          if (element instanceof PsiMethod){
            text = getTypeText(item, ((PsiMethod)element).getReturnType());
          }
          else if (element instanceof PsiVariable){
            PsiVariable variable = (PsiVariable)element;
            text = variable.getType().getPresentableText();
          }
          else if (element instanceof PsiExpression){
            PsiExpression expression = (PsiExpression)element;
            PsiType type = expression.getType();
            if (type != null){
              text = type.getPresentableText();
            }
          }
          else if (element instanceof BeanPropertyElement) {
            return getTypeText(item, ((BeanPropertyElement)element).getPropertyType());
          }
        }
      }
    }

    return text;
  }

  @Nullable
  private static String getTypeText(LookupItem item, @Nullable PsiType returnType) {
    if (returnType == null) {
      return null;
    }

    final PsiSubstitutor substitutor = (PsiSubstitutor)item.getAttribute(LookupItem.SUBSTITUTOR);
    if (substitutor != null) {
      return substitutor.substitute(returnType).getPresentableText();
    }
    return returnType.getPresentableText();
  }

  @Nullable
  private static String formatTypeName(final PsiClass element, final PsiSubstitutor substitutor) {
    final CodeStyleSettings styleSettings = CodeStyleSettingsManager.getSettings(element.getProject());
    String name = element.getName();
    if(substitutor != null){
      final PsiTypeParameter[] params = element.getTypeParameters();
      if(params.length > 0){
        StringBuffer buffer = new StringBuffer();
        buffer.append("<");
        boolean flag = true;
        for(int i = 0; i < params.length; i++){
          final PsiTypeParameter param = params[i];
          final PsiType type = substitutor.substitute(param);
          if(type == null){
            flag = false;
            break;
          }
          buffer.append(type.getPresentableText());
          if(i < params.length - 1){ buffer.append(",");
            if(styleSettings.SPACE_AFTER_COMMA) buffer.append(" ");
          }
        }
        buffer.append(">");
        if(flag) name += buffer;
      }
    }
    return name;
  }

  private static boolean isToStrikeout(LookupItem<?> item) {
    final List<PsiMethod> allMethods = item.getUserData(JavaCompletionUtil.ALL_METHODS_ATTRIBUTE);
    if (allMethods != null){
      for (PsiMethod method : allMethods) {
        if (!method.isValid()) { //?
          return false;
        }
        if (!isDeprecated(method)) {
          return false;
        }
      }
      return true;
    }
    else if (item.getObject() instanceof PsiElement) {
      final PsiElement element = (PsiElement)item.getObject();
      if (element.isValid()) {
        return isDeprecated(element);
      }
    }
    return false;
  }

  private static boolean isDeprecated(PsiElement element) {
    return element instanceof PsiDocCommentOwner && ((PsiDocCommentOwner)element).isDeprecated();
  }
}
