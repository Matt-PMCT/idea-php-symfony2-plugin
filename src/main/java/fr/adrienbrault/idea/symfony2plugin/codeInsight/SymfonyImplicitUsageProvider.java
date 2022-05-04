package fr.adrienbrault.idea.symfony2plugin.codeInsight;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.jetbrains.php.lang.parser.PhpElementTypes;
import com.jetbrains.php.lang.psi.elements.*;
import de.espend.idea.php.annotation.dict.PhpDocCommentAnnotation;
import de.espend.idea.php.annotation.util.AnnotationUtil;
import fr.adrienbrault.idea.symfony2plugin.routing.RouteHelper;
import fr.adrienbrault.idea.symfony2plugin.util.PhpElementsUtil;
import fr.adrienbrault.idea.symfony2plugin.util.dict.ServiceUtil;
import org.apache.commons.lang.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class SymfonyImplicitUsageProvider implements ImplicitUsageProvider {
    private static final String[] ROUTE_ANNOTATIONS = new String[]{
        "\\Symfony\\Component\\Routing\\Annotation\\Route",
        "\\Sensio\\Bundle\\FrameworkExtraBundle\\Configuration\\Route"
    };

    @Override
    public boolean isImplicitUsage(@NotNull PsiElement element) {
        if (element instanceof Method && ((Method) element).getAccess() == PhpModifier.Access.PUBLIC) {
            return isMethodARoute((Method) element)
                || isSubscribedEvent((Method) element);
        } else if (element instanceof PhpClass) {
            return isRouteClass((PhpClass) element)
                || isCommandAndService((PhpClass) element)
                || isSubscribedEvent((PhpClass) element);
        }

        return false;
    }

    private boolean isRouteClass(@NotNull PhpClass phpClass) {
        return phpClass.getMethods()
            .stream()
            .filter(method -> method.getAccess() == PhpModifier.Access.PUBLIC)
            .anyMatch(this::isMethodARoute);
    }

    private boolean isCommandAndService(PhpClass element) {
        return PhpElementsUtil.isInstanceOf(element, "\\Symfony\\Component\\Console\\Command\\Command")
            && ServiceUtil.isPhpClassAService(element);
    }

    @Override
    public boolean isImplicitRead(@NotNull PsiElement element) {
        return false;
    }

    @Override
    public boolean isImplicitWrite(@NotNull PsiElement element) {
        return false;
    }

    private boolean isMethodARoute(@NotNull Method method) {
        PhpDocCommentAnnotation phpDocCommentAnnotationContainer = AnnotationUtil.getPhpDocCommentAnnotationContainer(method.getDocComment());
        if (phpDocCommentAnnotationContainer != null && phpDocCommentAnnotationContainer.getFirstPhpDocBlock(ROUTE_ANNOTATIONS) != null) {
            return true;
        }

        for (String route : ROUTE_ANNOTATIONS) {
            Collection<@NotNull PhpAttribute> attributes = method.getAttributes(route);
            if (!attributes.isEmpty()) {
                return true;
            }
        }

        return RouteHelper.isRouteExistingForMethod(method);
    }

    private boolean isSubscribedEvent(@NotNull PhpClass phpClass) {
        return phpClass.getMethods()
            .stream()
            .filter(method -> method.getAccess() == PhpModifier.Access.PUBLIC)
            .anyMatch(this::isSubscribedEvent);
    }

    private boolean isSubscribedEvent(@NotNull Method method) {
        PhpClass containingClass = method.getContainingClass();
        if (containingClass == null || !PhpElementsUtil.isInstanceOf(containingClass, "\\Symfony\\Component\\EventDispatcher\\EventSubscriberInterface")) {
            return false;
        }

        Method subscribedEvents = containingClass.findMethodByName("getSubscribedEvents");
        if (subscribedEvents == null) {
            return false;
        }

        for (PhpReturn aReturn : PsiTreeUtil.collectElementsOfType(subscribedEvents, PhpReturn.class)) {
            PsiElement[] psiElements = PsiTreeUtil.collectElements(aReturn, element -> {
                if (!(element instanceof StringLiteralExpression)) {
                    return false;
                }

                PsiElement parent = element.getParent();
                return parent != null && parent.getNode().getElementType() == PhpElementTypes.ARRAY_VALUE && parent.getChildren().length == 1;
            });

            for (PsiElement psiElement : psiElements) {
                if (psiElement instanceof StringLiteralExpression) {
                    String contents = ((StringLiteralExpression) psiElement).getContents();
                    if (StringUtils.isNotBlank(contents) && contents.equalsIgnoreCase(method.getName())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }
}