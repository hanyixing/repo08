package run.halo.app.content.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import run.halo.app.content.CategoryService;
import run.halo.app.content.ListedPost;
import run.halo.app.content.PostQuery;
import run.halo.app.content.PostRequest;
import run.halo.app.content.Stats;
import run.halo.app.content.TestPost;
import run.halo.app.core.counter.CounterService;
import run.halo.app.core.counter.MeterUtils;
import run.halo.app.core.extension.Counter;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Category;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Tag;
import run.halo.app.core.user.service.UserService;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.PageRequest;
import run.halo.app.extension.ReactiveExtensionClient;

@ExtendWith(MockitoExtension.class)
class PostServiceImplTest {

    @Mock
    ReactiveExtensionClient client;

    @Mock
    CounterService counterService;

    @Mock
    UserService userService;

    @Mock
    CategoryService categoryService;

    @InjectMocks
    PostServiceImpl postService;

    private static Post createPost(String name, String owner) {
        Post post = TestPost.postV1();
        post.getMetadata().setName(name);
        post.getSpec().setOwner(owner);
        post.getSpec().setCategories(List.of("cat-1"));
        post.getSpec().setTags(List.of("tag-1"));
        return post;
    }

    private static User createUser(String name) {
        User user = new User();
        user.setMetadata(new Metadata());
        user.getMetadata().setName(name);
        user.setSpec(new User.UserSpec());
        user.getSpec().setDisplayName(name + "-displayName");
        user.getSpec().setAvatar(name + "-avatar");
        return user;
    }

    @Nested
    @DisplayName("ListPost")
    class ListPostTest {

        @Test
        void shouldListPostsSuccessfully() {
            var post = createPost("post-1", "alice");
            post.setStatus(new Post.PostStatus());
            post.getStatus().setContributors(List.of("alice"));

            var listResult = new ListResult<>(1, 10, 1, List.of(post));
            when(client.listBy(eq(Post.class), any(ListOptions.class), any(PageRequest.class)))
                    .thenReturn(Mono.just(listResult));

            Counter counter = new Counter();
            counter.setVisit(100);
            counter.setUpvote(10);
            counter.setTotalComment(5);
            counter.setApprovedComment(3);
            when(counterService.getByName(eq(MeterUtils.nameOf(Post.class, "post-1"))))
                    .thenReturn(Mono.just(counter));

            Tag tag = new Tag();
            tag.setMetadata(new Metadata());
            tag.getMetadata().setName("tag-1");
            when(client.listAll(eq(Tag.class), any(ListOptions.class), any()))
                    .thenReturn(Flux.just(tag));

            Category category = new Category();
            category.setMetadata(new Metadata());
            category.getMetadata().setName("cat-1");
            when(client.listAll(eq(Category.class), any(ListOptions.class), any()))
                    .thenReturn(Flux.just(category));

            when(userService.getUserOrGhost("alice")).thenReturn(Mono.just(createUser("alice")));

            var query = mock(PostQuery.class);
            when(query.toListOptions()).thenReturn(new ListOptions());
            when(query.toPageRequest()).thenReturn(mock(PageRequest.class));
            when(query.getCategoryWithChildren()).thenReturn(null);

            StepVerifier.create(postService.listPost(query))
                    .consumeNextWith(result -> {
                        assertThat(result.getTotal()).isEqualTo(1);
                        ListedPost listedPost = result.getItems().getFirst();
                        assertThat(listedPost.getPost()).isEqualTo(post);
                        assertThat(listedPost.getStats().getVisit()).isEqualTo(100);
                        assertThat(listedPost.getStats().getUpvote()).isEqualTo(10);
                        assertThat(listedPost.getOwner().getName()).isEqualTo("alice");
                        assertThat(listedPost.getOwner().getDisplayName()).isEqualTo("alice-displayName");
                        assertThat(listedPost.getTags()).hasSize(1);
                        assertThat(listedPost.getCategories()).hasSize(1);
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyWhenNoPosts() {
            var listResult = new ListResult<Post>(1, 10, 0, List.of());
            when(client.listBy(eq(Post.class), any(ListOptions.class), any(PageRequest.class)))
                    .thenReturn(Mono.just(listResult));

            var query = mock(PostQuery.class);
            when(query.toListOptions()).thenReturn(new ListOptions());
            when(query.toPageRequest()).thenReturn(mock(PageRequest.class));
            when(query.getCategoryWithChildren()).thenReturn(null);

            StepVerifier.create(postService.listPost(query))
                    .consumeNextWith(result -> {
                        assertThat(result.getTotal()).isZero();
                        assertThat(result.getItems()).isEmpty();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("FetchStats")
    class FetchStatsTest {

        @Test
        void shouldFetchStatsSuccessfully() {
            var post = createPost("post-1", "alice");
            Counter counter = new Counter();
            counter.setVisit(50);
            counter.setUpvote(5);
            counter.setTotalComment(3);
            counter.setApprovedComment(2);

            when(counterService.getByName(eq(MeterUtils.nameOf(Post.class, "post-1"))))
                    .thenReturn(Mono.just(counter));

            StepVerifier.create(postService.fetchStats(post))
                    .consumeNextWith(stats -> {
                        assertThat(stats.getVisit()).isEqualTo(50);
                        assertThat(stats.getUpvote()).isEqualTo(5);
                        assertThat(stats.getTotalComment()).isEqualTo(3);
                        assertThat(stats.getApprovedComment()).isEqualTo(2);
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyStatsWhenCounterNotFound() {
            var post = createPost("post-1", "alice");
            when(counterService.getByName(eq(MeterUtils.nameOf(Post.class, "post-1"))))
                    .thenReturn(Mono.empty());

            StepVerifier.create(postService.fetchStats(post))
                    .consumeNextWith(stats -> {
                        assertThat(stats.getVisit()).isZero();
                        assertThat(stats.getUpvote()).isZero();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Publish")
    class PublishTest {

        @Test
        void shouldPublishPostSuccessfully() {
            var post = createPost("post-1", "alice");
            post.getSpec().setHeadSnapshot("snapshot-1");
            post.getSpec().setBaseSnapshot("base-snapshot");

            when(client.update(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(postService.publish(post))
                    .consumeNextWith(published -> {
                        assertThat(published.getSpec().getPublish()).isTrue();
                        assertThat(published.getSpec().getReleaseSnapshot()).isEqualTo("snapshot-1");
                    })
                    .verifyComplete();
        }

        @Test
        void shouldPublishPostWithBaseSnapshotWhenHeadIsNull() {
            var post = createPost("post-1", "alice");
            post.getSpec().setHeadSnapshot(null);
            post.getSpec().setBaseSnapshot("base-snapshot");

            when(client.update(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(postService.publish(post))
                    .consumeNextWith(published -> {
                        assertThat(published.getSpec().getPublish()).isTrue();
                        assertThat(published.getSpec().getHeadSnapshot()).isEqualTo("base-snapshot");
                        assertThat(published.getSpec().getReleaseSnapshot()).isEqualTo("base-snapshot");
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("Unpublish")
    class UnpublishTest {

        @Test
        void shouldUnpublishPostSuccessfully() {
            var post = createPost("post-1", "alice");
            post.getSpec().setPublish(true);

            when(client.update(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(postService.unpublish(post))
                    .consumeNextWith(unpublished -> {
                        assertThat(unpublished.getSpec().getPublish()).isFalse();
                    })
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GetByUsername")
    class GetByUsernameTest {

        @Test
        void shouldGetPostWhenOwnerMatches() {
            var post = createPost("post-1", "alice");
            when(client.get(Post.class, "post-1")).thenReturn(Mono.just(post));

            StepVerifier.create(postService.getByUsername("post-1", "alice"))
                    .consumeNextWith(result -> {
                        assertThat(result.getSpec().getOwner()).isEqualTo("alice");
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyWhenOwnerNotMatch() {
            var post = createPost("post-1", "alice");
            when(client.get(Post.class, "post-1")).thenReturn(Mono.just(post));

            StepVerifier.create(postService.getByUsername("post-1", "bob"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("RecycleBy")
    class RecycleByTest {

        @Test
        void shouldRecyclePostSuccessfully() {
            var post = createPost("post-1", "alice");
            post.getSpec().setDeleted(false);

            when(client.get(Post.class, "post-1")).thenReturn(Mono.just(post));
            when(client.update(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(postService.recycleBy("post-1", "alice"))
                    .consumeNextWith(recycled -> {
                        assertThat(recycled.getSpec().getDeleted()).isTrue();
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyWhenOwnerNotMatchForRecycle() {
            var post = createPost("post-1", "alice");
            when(client.get(Post.class, "post-1")).thenReturn(Mono.just(post));

            StepVerifier.create(postService.recycleBy("post-1", "bob"))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("DraftPost")
    class DraftPostTest {

        @Test
        void shouldDraftPostWithoutContent() {
            var post = createPost("post-1", "alice");
            var postRequest = new PostRequest(post, null);

            when(client.create(any(Post.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

            StepVerifier.create(postService.draftPost(postRequest))
                    .consumeNextWith(created -> {
                        assertThat(created.getMetadata().getName()).isEqualTo("post-1");
                    })
                    .verifyComplete();

            verify(client).create(any(Post.class));
        }
    }

    @Nested
    @DisplayName("UpdateBy")
    class UpdateByTest {

        @Test
        void shouldUpdatePostSuccessfully() {
            var post = createPost("post-1", "alice");
            when(client.update(post)).thenReturn(Mono.just(post));

            StepVerifier.create(postService.updateBy(post))
                    .expectNext(post)
                    .verifyComplete();

            verify(client).update(post);
        }
    }

    @Nested
    @DisplayName("ListCategories")
    class ListCategoriesTest {

        @Test
        void shouldListCategoriesInOrder() {
            var cat1 = new Category();
            cat1.setMetadata(new Metadata());
            cat1.getMetadata().setName("cat-1");

            var cat2 = new Category();
            cat2.setMetadata(new Metadata());
            cat2.getMetadata().setName("cat-2");

            when(client.listAll(eq(Category.class), any(ListOptions.class), any()))
                    .thenReturn(Flux.just(cat2, cat1));

            StepVerifier.create(postService.listCategories(List.of("cat-1", "cat-2")))
                    .consumeNextWith(categories -> {
                        // Should be ordered according to the input list
                        assertThat(categories.get(0).getMetadata().getName()).isEqualTo("cat-1");
                        assertThat(categories.get(1).getMetadata().getName()).isEqualTo("cat-2");
                    })
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyWhenCategoryNamesIsNull() {
            StepVerifier.create(postService.listCategories(null))
                    .verifyComplete();
        }

        @Test
        void shouldReturnEmptyWhenCategoryNamesIsEmpty() {
            StepVerifier.create(postService.listCategories(List.of()))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GetHeadContent")
    class GetHeadContentTest {

        @Test
        void shouldReturnEmptyWhenHeadSnapshotIsBlank() {
            var post = createPost("post-1", "alice");
            post.getSpec().setHeadSnapshot(null);
            post.getSpec().setBaseSnapshot(null);

            StepVerifier.create(postService.getHeadContent(post))
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("GetReleaseContent")
    class GetReleaseContentTest {

        @Test
        void shouldReturnEmptyWhenReleaseSnapshotIsBlank() {
            var post = createPost("post-1", "alice");
            post.getSpec().setReleaseSnapshot(null);
            post.getSpec().setBaseSnapshot(null);

            StepVerifier.create(postService.getReleaseContent(post))
                    .verifyComplete();
        }
    }
}
