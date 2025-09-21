package dev.fumaz.infuse.injector;

import dev.fumaz.infuse.annotation.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConstructorResolutionTest {

    static class Dependency {
    }

    static class Menu {
        private final Dependency dependency;

        Menu(Dependency dependency) {
            this.dependency = dependency;
        }
    }

    static class ParentMenu {
    }

    static class AllowsNullParent {
        private final OnlineAccount account;
        private final ParentMenu parent;

        AllowsNullParent(OnlineAccount account, ParentMenu parent) {
            this.account = account;
            this.parent = parent;
        }

        OnlineAccount getAccount() {
            return account;
        }

        ParentMenu getParent() {
            return parent;
        }
    }

    static class OnlineAccount {
    }

    static class YourMapsMenu {
        private final Menu menu;

        @Inject
        YourMapsMenu(Menu menu) {
            this.menu = menu;
        }
    }

    @Test
    void shouldConstructTypeWithSingleConstructorDependency() {
        Injector injector = Injector.create();

        YourMapsMenu instance = injector.construct(YourMapsMenu.class);

        assertNotNull(instance);
        assertNotNull(instance.menu);
    }

    @Test
    void shouldRespectExplicitNullArguments() {
        Injector injector = Injector.create();

        OnlineAccount account = new OnlineAccount();
        AllowsNullParent instance = injector.construct(AllowsNullParent.class, account, null);

        assertNotNull(instance);
        assertSame(account, instance.getAccount());
        assertNull(instance.getParent());
    }
}
