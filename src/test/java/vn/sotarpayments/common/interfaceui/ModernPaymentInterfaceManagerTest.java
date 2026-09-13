package vn.sotarpayments.common.interfaceui;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModernPaymentInterfaceManagerTest {
    @Test
    void resolvesBuilderMethodsThroughTheirPublicApiInterface() throws Exception {
        Method finder = reflectionMethodFinder();

        HiddenBuilder builder = new HiddenBuilder();
        Method uses = (Method) finder.invoke(
                null, HiddenBuilder.class, "uses", false, new Object[]{1});
        Method build = (Method) finder.invoke(
                null, HiddenBuilder.class, "build", false, new Object[]{});

        assertEquals(PublicBuilder.class, uses.getDeclaringClass());
        assertEquals(PublicBuilder.class, build.getDeclaringClass());
        assertSame(builder, uses.invoke(builder, 1));
        assertEquals(1, build.invoke(builder));
    }

    @Test
    void invokesRealAdventureClickOptionsBuilderWithoutIllegalAccess() throws Exception {
        Class<?> optionsType = Class.forName(
                "net.kyori.adventure.text.event.ClickCallback$Options");
        Object builder = optionsType.getMethod("builder").invoke(null);
        assertFalse(Modifier.isPublic(builder.getClass().getModifiers()));

        Method finder = reflectionMethodFinder();
        Method uses = (Method) finder.invoke(
                null, builder.getClass(), "uses", false, new Object[]{1});
        Method build = (Method) finder.invoke(
                null, builder.getClass(), "build", false, new Object[]{});

        assertTrue(Modifier.isPublic(uses.getDeclaringClass().getModifiers()));
        assertTrue(Modifier.isPublic(build.getDeclaringClass().getModifiers()));
        assertSame(builder, uses.invoke(builder, 1));
        Object options = build.invoke(builder);
        assertEquals(1, optionsType.getMethod("uses").invoke(options));
    }

    private Method reflectionMethodFinder() throws Exception {
        Class<?> dialogApi = Class.forName(
                ModernPaymentInterfaceManager.class.getName() + "$DialogApi");
        Method finder = dialogApi.getDeclaredMethod(
                "findCompatibleMethod", Class.class, String.class, boolean.class, Object[].class);
        finder.setAccessible(true);
        return finder;
    }

    public interface PublicBuilder {
        PublicBuilder uses(int uses);

        int build();
    }

    private static final class HiddenBuilder implements PublicBuilder {
        private int uses;

        @Override
        public PublicBuilder uses(int uses) {
            this.uses = uses;
            return this;
        }

        @Override
        public int build() {
            return uses;
        }
    }
}
