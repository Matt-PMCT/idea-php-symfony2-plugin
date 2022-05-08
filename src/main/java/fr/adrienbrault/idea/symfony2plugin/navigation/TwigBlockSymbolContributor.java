package fr.adrienbrault.idea.symfony2plugin.navigation;

import com.intellij.navigation.ChooseByNameContributor;
import com.intellij.navigation.NavigationItem;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.util.indexing.FileBasedIndex;
import com.jetbrains.twig.TwigFile;
import fr.adrienbrault.idea.symfony2plugin.Symfony2Icons;
import fr.adrienbrault.idea.symfony2plugin.Symfony2ProjectComponent;
import fr.adrienbrault.idea.symfony2plugin.stubs.cache.FileIndexCaches;
import fr.adrienbrault.idea.symfony2plugin.stubs.indexes.TwigBlockIndexExtension;
import fr.adrienbrault.idea.symfony2plugin.templating.dict.TwigBlock;
import fr.adrienbrault.idea.symfony2plugin.templating.util.TwigUtil;
import fr.adrienbrault.idea.symfony2plugin.util.PsiElementUtils;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author Daniel Espendiller <daniel@espendiller.net>
 */
public class TwigBlockSymbolContributor implements ChooseByNameContributor {
    private static final Key<CachedValue<String[]>> SYMFONY_TWIG_BLOCK_NAMES = new Key<>("SYMFONY_TWIG_BLOCK_NAMES");

    @Override
    public String @NotNull [] getNames(Project project, boolean b) {
        if(!Symfony2ProjectComponent.isEnabled(project)) {
            return new String[0];
        }

        return CachedValuesManager.getManager(project).getCachedValue(
            project,
            SYMFONY_TWIG_BLOCK_NAMES,
            () -> {
                String[] blocks = FileBasedIndex.getInstance()
                    .getValues(TwigBlockIndexExtension.KEY, "block", GlobalSearchScope.allScope(project))
                    .stream()
                    .flatMap(Collection::stream)
                    .distinct()
                    .toArray(String[]::new);

                return CachedValueProvider.Result.create(blocks, FileIndexCaches.getModificationTrackerForIndexId(project, TwigBlockIndexExtension.KEY));
            },
            false
        );
    }

    @NotNull
    @Override
    public NavigationItem[] getItemsByName(String name, String s2, Project project, boolean b) {
        if(!Symfony2ProjectComponent.isEnabled(project)) {
            return new NavigationItem[0];
        }

        FileBasedIndex.AllKeysQuery<String, Set<String>> query = new FileBasedIndex.AllKeysQuery<>(
            TwigBlockIndexExtension.KEY,
            List.of("block"),
            strings -> strings.contains(name)
        );

        Set<VirtualFile> virtualFiles = new HashSet<>();
        FileBasedIndex.getInstance().processFilesContainingAllKeys(List.of(query), GlobalSearchScope.allScope(project), virtualFile -> {
            virtualFiles.add(virtualFile);
            return true;
        });

        Collection<NavigationItem> navigationItems = new HashSet<>();

        for (PsiFile psiFile : PsiElementUtils.convertVirtualFilesToPsiFiles(project, virtualFiles)) {
            if (psiFile instanceof TwigFile) {
                navigationItems.addAll(TwigUtil.getBlocksInFile((TwigFile) psiFile).stream().map((Function<TwigBlock, NavigationItem>) twigBlock -> new NavigationItemEx(twigBlock.getTarget(), twigBlock.getName(), Symfony2Icons.TWIG_BLOCK_OVERWRITE, "Block")).collect(Collectors.toSet()));
            }
        }

        return navigationItems.toArray(new NavigationItem[0]);
    }
}
