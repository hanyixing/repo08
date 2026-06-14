package run.halo.app.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.model.entity.Category;
import run.halo.app.model.entity.Content.PatchedContent;
import run.halo.app.model.entity.Post;
import run.halo.app.model.entity.Tag;
import run.halo.app.model.enums.PostStatus;
import run.halo.app.service.PostCategoryService;
import run.halo.app.service.PostMetaService;
import run.halo.app.service.PostTagService;

/**
 * Runnable unit tests for {@link PostServiceImpl#exportMarkdown(Post)}.
 *
 * <p>This complements the Spring-context based {@code PostServiceImplTest} (which is disabled)
 * with a pure Mockito test that exercises the Markdown front-matter generation without booting
 * the application context.</p>
 */
@ExtendWith(MockitoExtension.class)
class PostServiceImplExportMarkdownTest {

    @Mock
    PostTagService postTagService;

    @Mock
    PostCategoryService postCategoryService;

    @Mock
    PostMetaService postMetaService;

    private PostServiceImpl postService;

    @BeforeEach
    void setUp() {
        // Only the three list services are exercised by exportMarkdown(Post); the remaining
        // collaborators are not used and are therefore passed as null.
        postService = new PostServiceImpl(
            null,               // basePostRepository
            null,               // postAssembler
            null,               // optionService
            null,               // postRepository
            null,               // tagService
            null,               // categoryService
            postTagService,
            postCategoryService,
            null,               // postCommentService
            null,               // eventPublisher
            postMetaService,
            null,               // contentService
            null,               // contentPatchLogService
            null                // applicationContext
        );
    }

    private Post samplePost() {
        Post post = new Post();
        post.setId(1);
        post.setTitle("Hello World");
        post.setSlug("hello-world");
        post.setStatus(PostStatus.PUBLISHED);
        post.setThumbnail("thumbnail.png");
        post.setDisallowComment(false);
        post.setContent(new PatchedContent("<p>body</p>", "# Title\nbody text"));
        return post;
    }

    @Test
    @DisplayName("exportMarkdown renders front-matter with tags and categories")
    void exportMarkdownWithTagsAndCategories() {
        Tag tag = new Tag();
        tag.setName("Java");
        Category category = new Category();
        category.setName("Tech");

        when(postTagService.listTagsBy(1)).thenReturn(List.of(tag));
        when(postCategoryService.listCategoriesBy(1)).thenReturn(List.of(category));

        String markdown = postService.exportMarkdown(samplePost());

        assertTrue(markdown.startsWith("---"));
        assertTrue(markdown.contains("type: post"));
        assertTrue(markdown.contains("title: Hello World"));
        assertTrue(markdown.contains("permalink: hello-world"));
        // !disallowComment -> comments enabled
        assertTrue(markdown.contains("comments: true"));
        assertTrue(markdown.contains("tags:"));
        assertTrue(markdown.contains("- Java"));
        assertTrue(markdown.contains("categories:"));
        assertTrue(markdown.contains("- Tech"));
        // original markdown content is appended after the front-matter
        assertTrue(markdown.contains("# Title"));
        assertTrue(markdown.contains("body text"));
    }

    @Test
    @DisplayName("exportMarkdown omits tag/category blocks when there are none")
    void exportMarkdownWithoutTagsAndCategories() {
        // postTagService / postCategoryService return empty lists by default

        String markdown = postService.exportMarkdown(samplePost());

        assertTrue(markdown.contains("title: Hello World"));
        assertFalse(markdown.contains("tags:"));
        assertFalse(markdown.contains("categories:"));
        assertTrue(markdown.contains("# Title"));
    }

    @Test
    @DisplayName("exportMarkdown rejects a null post")
    void exportMarkdownRejectsNullPost() {
        assertThrows(IllegalArgumentException.class,
            () -> postService.exportMarkdown((Post) null));
    }
}
