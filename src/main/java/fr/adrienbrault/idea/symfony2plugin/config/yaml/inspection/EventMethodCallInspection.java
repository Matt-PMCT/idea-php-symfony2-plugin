package fr.adrienbrault.idea.symfony2plugin.config.yaml.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.lang.Language;
import com.intellij.lang.xml.XMLLanguage;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.patterns.StandardPatterns;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.PhpLanguage;
import com.jetbrains.php.lang.parser.PhpElementTypes;
import com.jetbrains.php.lang.psi.elements.*;
import fr.adrienbrault.idea.symfony2plugin.Symfony2ProjectComponent;
import fr.adrienbrault.idea.symfony2plugin.codeInspection.quickfix.CreateMethodQuickFix;
import fr.adrienbrault.idea.symfony2plugin.config.EventDispatcherSubscriberUtil;
import fr.adrienbrault.idea.symfony2plugin.config.xml.XmlHelper;
import fr.adrienbrault.idea.symfony2plugin.config.yaml.YamlElementPatternHelper;
import fr.adrienbrault.idea.symfony2plugin.stubs.ContainerCollectionResolver;
import fr.adrienbrault.idea.symfony2plugin.util.AnnotationBackportUtil;
import fr.adrienbrault.idea.symfony2plugin.util.EventSubscriberUtil;
import fr.adrienbrault.idea.symfony2plugin.util.PhpElementsUtil;
import fr.adrienbrault.idea.symfony2plugin.util.PsiElementUtils;
import fr.adrienbrault.idea.symfony2plugin.util.dict.ServiceUtil;
import fr.adrienbrault.idea.symfony2plugin.util.yaml.YamlHelper;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.YAMLLanguage;
import org.jetbrains.yaml.YAMLTokenTypes;
import org.jetbrains.yaml.psi.YAMLScalar;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class EventMethodCallInspection extends LocalInspectionTool {

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(final @NotNull ProblemsHolder holder, boolean isOnTheFly) {
        if(!Symfony2ProjectComponent.isEnabled(holder.getProject())) {
            return super.buildVisitor(holder, isOnTheFly);
        }

        return new PsiElementVisitor() {
            private NotNullLazyValue<ContainerCollectionResolver.LazyServiceCollector> serviceCollector;

            @Override
            public void visitElement(@NotNull PsiElement element) {
                Language language = element.getLanguage();

                if (language == YAMLLanguage.INSTANCE) {
                    if (this.serviceCollector == null) {
                        this.serviceCollector = NotNullLazyValue.lazy(() -> new ContainerCollectionResolver.LazyServiceCollector(holder.getProject()));
                    }

                    visitYmlElement(element, holder, this.serviceCollector);
                } else if (language == XMLLanguage.INSTANCE) {
                    if (this.serviceCollector == null) {
                        this.serviceCollector = NotNullLazyValue.lazy(() -> new ContainerCollectionResolver.LazyServiceCollector(holder.getProject()));
                    }

                    visitXmlElement(element, holder, this.serviceCollector);
                } else if (language == PhpLanguage.INSTANCE) {
                    if (element instanceof StringLiteralExpression stringLiteralExpression) {
                        visitPhpElement(stringLiteralExpression, holder);
                    }
                }

                super.visitElement(element);
            }
        };
    }

    public void visitXmlElement(@NotNull PsiElement element, @NotNull final ProblemsHolder holder, @NotNull NotNullLazyValue<ContainerCollectionResolver.LazyServiceCollector> collector) {
        boolean isSupportedTag = XmlHelper.getTagAttributePattern("tag", "method").inside(XmlHelper.getInsideTagPattern("services")).inFile(XmlHelper.getXmlFilePattern()).accepts(element)
            || XmlHelper.getTagAttributePattern("call", "method").inside(XmlHelper.getInsideTagPattern("services")).inFile(XmlHelper.getXmlFilePattern()).accepts(element);

        if (isSupportedTag) {
            // attach to text child only
            PsiElement[] psiElements = element.getChildren();
            if (psiElements.length < 2) {
                return;
            }

            String serviceClassValue = XmlHelper.getServiceDefinitionClass(element);
            if (StringUtils.isNotBlank(serviceClassValue)) {
                registerMethodProblem(psiElements[1], holder, serviceClassValue, collector);
            }

        }
    }

    private void visitYamlMethodTagKey(@NotNull final PsiElement psiElement, @NotNull ProblemsHolder holder, @NotNull NotNullLazyValue<ContainerCollectionResolver.LazyServiceCollector> collector) {

        String methodName = PsiElementUtils.trimQuote(psiElement.getText());
        if(StringUtils.isBlank(methodName)) {
            return;
        }

        String classValue = YamlHelper.getServiceDefinitionClassFromTagMethod(psiElement);
        if(classValue == null) {
            return;
        }

        registerMethodProblem(psiElement, holder, classValue, collector);
    }

    private void visitYmlElement(@NotNull final PsiElement psiElement, @NotNull ProblemsHolder holder, @NotNull NotNullLazyValue<ContainerCollectionResolver.LazyServiceCollector> collector) {
        if(StandardPatterns.and(
            YamlElementPatternHelper.getInsideKeyValue("tags"),
            YamlElementPatternHelper.getSingleLineScalarKey("method")
        ).accepts(psiElement)) {
            visitYamlMethodTagKey(psiElement, holder, collector);
        }

        if((PlatformPatterns.psiElement(YAMLTokenTypes.TEXT).accepts(psiElement)
            || PlatformPatterns.psiElement(YAMLTokenTypes.SCALAR_DSTRING).accepts(psiElement)))
        {
            visitYamlMethod(psiElement, holder, collector);
        }
    }

    private void visitYamlMethod(@NotNull PsiElement psiElement, @NotNull ProblemsHolder holder, @NotNull NotNullLazyValue<ContainerCollectionResolver.LazyServiceCollector> collector) {
        if(YamlElementPatternHelper.getInsideKeyValue("calls").accepts(psiElement)) {
            PsiElement parent = psiElement.getParent();
            if ((parent instanceof YAMLScalar)) {
                YamlHelper.visitServiceCall((YAMLScalar) parent, s ->
                    registerMethodProblem(psiElement, holder, YamlHelper.trimSpecialSyntaxServiceName(s), collector)
                );
            }
        }
    }

    private void registerMethodProblem(final @NotNull PsiElement psiElement, @NotNull ProblemsHolder holder, @NotNull String classKeyValue, @NotNull NotNullLazyValue<ContainerCollectionResolver.LazyServiceCollector> collector) {
        registerMethodProblem(psiElement, holder, ServiceUtil.getResolvedClassDefinition(psiElement.getProject(), classKeyValue, collector.get()));
    }

    private static void registerMethodProblem(final @NotNull PsiElement psiElement, @NotNull ProblemsHolder holder, @Nullable PhpClass phpClass) {
        if(phpClass == null) {
            return;
        }

        final String methodName = PsiElementUtils.trimQuote(psiElement.getText());
        if(phpClass.findMethodByName(methodName) != null) {
            return;
        }

        holder.registerProblem(
            psiElement,
            "Missing Method",
            ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
            new CreateMethodQuickFix(phpClass, methodName, new MyCreateMethodQuickFix())
        );
    }

    private static class MyCreateMethodQuickFix implements CreateMethodQuickFix.InsertStringInterface {
        @NotNull
        @Override
        public StringBuilder getStringBuilder(@NotNull ProblemDescriptor problemDescriptor, @NotNull PhpClass phpClass, @NotNull String functionName) {
            String taggedEventMethodParameter = getEventTypeHint(problemDescriptor, phpClass);

            String parameter = "";
            if(taggedEventMethodParameter != null) {
                parameter = taggedEventMethodParameter + " $event";
            }

            return new StringBuilder()
                .append("public function ")
                .append(functionName)
                .append("(")
                .append(parameter)
                .append(")\n {\n}\n\n");
        }

        @Nullable
        private String getEventTypeHint(@NotNull ProblemDescriptor problemDescriptor, @NotNull PhpClass phpClass) {
            String eventName = EventDispatcherSubscriberUtil.getEventNameFromScope(problemDescriptor.getPsiElement());
            if (eventName == null) {
                return null;
            }

            Collection<String> taggedEventMethodParameter = EventSubscriberUtil.getTaggedEventMethodParameter(problemDescriptor.getPsiElement().getProject(), eventName);
            if (taggedEventMethodParameter.isEmpty()) {
                return null;
            }

            return taggedEventMethodParameter.stream()
                .map(fqn -> importIfNecessary(phpClass, fqn))
                .collect(Collectors.joining("|"));
        }

        private String importIfNecessary(@NotNull PhpClass phpClass, String fqn) {
            String qualifiedName = AnnotationBackportUtil.getQualifiedName(phpClass, fqn);
            if (qualifiedName != null && !qualifiedName.equals(StringUtils.stripStart(fqn, "\\"))) {
                // class already imported
                return qualifiedName;
            }

            return PhpElementsUtil.insertUseIfNecessary(phpClass, fqn);
        }
    }

    /**
     * getSubscribedEvents method quick fix check
     *
     * return array(
     *   ConsoleEvents::COMMAND => array('onCommanda', 255),
     *   ConsoleEvents::TERMINATE => array('onTerminate', -255),
     * );
     *
     */
    private static void visitPhpElement(@NotNull StringLiteralExpression element, @NotNull ProblemsHolder holder) {
        PsiElement parent = element.getParent();
        if (parent != null && parent.getNode().getElementType() == PhpElementTypes.ARRAY_VALUE) {
            PhpReturn phpReturn = PsiTreeUtil.getParentOfType(parent, PhpReturn.class);
            if (phpReturn != null) {
                Method method = PsiTreeUtil.getParentOfType(parent, Method.class);
                if (method != null) {
                    String name = method.getName();
                    if ("getSubscribedEvents".equals(name)) {
                        PhpClass containingClass = method.getContainingClass();
                        if (containingClass != null && PhpElementsUtil.isInstanceOf(containingClass, "\\Symfony\\Component\\EventDispatcher\\EventSubscriberInterface")) {
                            String contents = element.getContents();
                            if (StringUtils.isNotBlank(contents) && containingClass.findMethodByName(contents) == null) {
                                registerMethodProblem(element, holder, containingClass);
                            }
                        }
                    }
                }
            }
        }

        if (parent instanceof ParameterList parameterList && PhpElementsUtil.isAttributeNamedArgumentString(element, "\\Symfony\\Component\\EventDispatcher\\Attribute\\AsEventListener", "method")) {
            PhpAttribute parentOfType = PsiTreeUtil.getParentOfType(parameterList, PhpAttribute.class);
            if (parentOfType.getOwner() instanceof PhpClass phpClass) {
                String contents = element.getContents();
                if (!contents.isBlank() && phpClass.findMethodByName(contents) == null) {
                    registerMethodProblem(element, holder, phpClass);
                }
            }
        }
    }
}
