package vn.sotarpayments.common.interfaceui;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import vn.sotarpayments.SotarPayments;
import vn.sotarpayments.common.model.PaymentChannel;
import vn.sotarpayments.napcard.utils.CardProviderNames;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Optional Paper/Leaf Dialog UI bridge.
 *
 * <p>The plugin is compiled against Spigot, so every Paper-specific type is
 * resolved reflectively. Paper/Leaf runtimes exposing the Dialog API receive
 * native dialogs while Bukkit, Spigot, and older Paper/Folia builds keep the inventory/chat
 * workflow without a hard linkage error.</p>
 */
public final class ModernPaymentInterfaceManager {
    private static final int DEFAULT_BUTTON_WIDTH = 200;

    private final SotarPayments plugin;
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);
    private volatile DialogApi dialogApi;
    private volatile boolean availabilityChecked;

    public ModernPaymentInterfaceManager(SotarPayments plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    public void reload() {
        availabilityChecked = false;
        dialogApi = null;
        failureLogged.set(false);
    }

    public boolean isDialogAvailable() {
        return plugin.config().getBoolean("interface.prefer-dialog", true) && api() != null;
    }

    public void openCardProvider(Player player) {
        if (player == null) return;
        if (!openCardProviderDialog(player)) {
            plugin.getPaymentGuiManager().openCardProviderMenu(player);
        }
    }

    public void openBankAmount(Player player) {
        if (player == null) return;
        if (!openBankAmountDialog(player)) {
            plugin.getPaymentGuiManager().openBankAmountMenu(player);
        }
    }

    private boolean openCardProviderDialog(Player player) {
        DialogApi api = api();
        if (api == null) return false;

        List<String> telcos = plugin.getPaymentGuiManager().getCardTelcos();
        if (telcos.isEmpty()) return false;

        List<DialogButton> buttons = new ArrayList<>(telcos.size());
        for (String telco : telcos) {
            String normalized = CardProviderNames.normalize(telco);
            buttons.add(new DialogButton("&b" + displayTelco(normalized), DEFAULT_BUTTON_WIDTH,
                    (response, audience) -> plugin.getPlatformScheduler().runPlayer(audience,
                            () -> openCardAmount(audience, normalized))));
        }

        return showSafely(() -> api.showMultiAction(
                player,
                plugin.tr("dialog.card-provider-title"),
                plugin.tr("dialog.card-provider-body",
                        "provider", plugin.getCardChargingService().getProviderDisplayName()),
                buttons,
                Math.min(3, Math.max(1, buttons.size()))
        ));
    }

    private void openCardAmount(Player player, String telco) {
        DialogApi api = api();
        if (api == null) {
            plugin.getPaymentGuiManager().openCardAmountMenu(player, telco);
            return;
        }

        List<Integer> amounts = plugin.getPaymentGuiManager().getCardAmounts();
        if (amounts.isEmpty()) {
            plugin.getPaymentGuiManager().openCardAmountMenu(player, telco);
            return;
        }

        List<DialogButton> buttons = new ArrayList<>(amounts.size());
        for (int amount : amounts) {
            double taxRate = plugin.isCardTaxesEnabled()
                    ? plugin.getCardRateManager().getDiscountRate(telco, amount)
                    : 0.0D;
            int netAmount = Math.max(0, (int) (amount * (1.0D - taxRate / 100.0D)));
            int basePoints = netAmount / plugin.getCardRewardRatio();
            int points = plugin.applyPromotionToPoints(basePoints, PaymentChannel.CARD);
            String label = "&a" + plugin.formatMoney(amount) + " VNĐ\n&7≈ "
                    + plugin.formatMoney(points) + " Points";
            buttons.add(new DialogButton(label, DEFAULT_BUTTON_WIDTH,
                    (response, audience) -> plugin.getPlatformScheduler().runPlayer(audience,
                            () -> openCardCredentials(audience, telco, amount))));
        }

        boolean shown = showSafely(() -> api.showMultiAction(
                player,
                plugin.tr("dialog.card-amount-title", "telco", displayTelco(telco)),
                plugin.tr("dialog.card-amount-body"),
                buttons,
                2
        ));
        if (!shown) {
            plugin.getPaymentGuiManager().openCardAmountMenu(player, telco);
        }
    }

    private void openCardCredentials(Player player, String telco, int amount) {
        DialogApi api = api();
        if (api == null) {
            startLegacyCardInput(player, telco, amount);
            return;
        }

        List<TextInput> inputs = List.of(
                new TextInput("serial", plugin.tr("dialog.serial-label"), 300, 64),
                new TextInput("pin", plugin.tr("dialog.pin-label"), 300, 64)
        );
        boolean shown = showSafely(() -> api.showConfirmation(
                player,
                plugin.tr("dialog.card-input-title",
                        "telco", displayTelco(telco),
                        "amount", plugin.formatMoney(amount)),
                plugin.tr("dialog.card-input-body"),
                inputs,
                plugin.tr("dialog.confirm"),
                plugin.tr("dialog.cancel"),
                (response, audience) -> {
                    String serial = api.readText(response, "serial").trim();
                    String pin = api.readText(response, "pin").trim();
                    plugin.getPlatformScheduler().runPlayer(audience,
                            () -> submitCardDialog(audience, telco, amount, serial, pin));
                }
        ));
        if (!shown) {
            startLegacyCardInput(player, telco, amount);
        }
    }

    private void submitCardDialog(Player player, String telco, int amount, String serial, String pin) {
        if (serial.isBlank() || pin.isBlank()) {
            player.sendMessage(plugin.tr("dialog.card-input-invalid"));
            openCardCredentials(player, telco, amount);
            return;
        }

        if (!plugin.getCardFlowManager().submitCredentials(player, telco, amount, serial, pin)) {
            openCardCredentials(player, telco, amount);
        }
    }

    private void startLegacyCardInput(Player player, String telco, int amount) {
        plugin.getCardFlowManager().startChatInput(player, telco, amount);
    }

    private boolean openBankAmountDialog(Player player) {
        DialogApi api = api();
        if (api == null) return false;

        List<Long> amounts = plugin.getPaymentGuiManager().getBankAmounts();
        List<DialogButton> buttons = new ArrayList<>(amounts.size() + 1);
        for (long amount : amounts) {
            int points = plugin.calculateFinalPoints(amount, PaymentChannel.BANK);
            String label = "&a" + plugin.formatMoney(amount) + " VNĐ\n&7≈ "
                    + plugin.formatMoney(points) + " Points";
            buttons.add(new DialogButton(label, DEFAULT_BUTTON_WIDTH,
                    (response, audience) -> plugin.getPlatformScheduler().runPlayer(audience,
                            () -> audience.performCommand("bank " + amount))));
        }
        buttons.add(new DialogButton(plugin.tr("dialog.custom-amount"), DEFAULT_BUTTON_WIDTH,
                (response, audience) -> plugin.getPlatformScheduler().runPlayer(audience,
                        () -> openCustomBankAmount(audience))));

        return showSafely(() -> api.showMultiAction(
                player,
                plugin.tr("dialog.bank-title"),
                plugin.tr("dialog.bank-body"),
                buttons,
                2
        ));
    }

    private void openCustomBankAmount(Player player) {
        DialogApi api = api();
        if (api == null) {
            player.sendMessage(plugin.tr("bank.usage"));
            return;
        }

        boolean shown = showSafely(() -> api.showConfirmation(
                player,
                plugin.tr("dialog.bank-custom-title"),
                plugin.tr("dialog.bank-custom-body"),
                List.of(new TextInput("amount", plugin.tr("dialog.amount-label"), 300, 16)),
                plugin.tr("dialog.confirm"),
                plugin.tr("dialog.cancel"),
                (response, audience) -> {
                    String rawAmount = api.readText(response, "amount")
                            .replace(".", "")
                            .replace(",", "")
                            .replace(" ", "")
                            .trim();
                    plugin.getPlatformScheduler().runPlayer(audience, () -> {
                        if (!rawAmount.matches("[0-9]{1,16}")) {
                            audience.sendMessage(plugin.tr("bank.invalid-amount"));
                            openCustomBankAmount(audience);
                            return;
                        }
                        audience.performCommand("bank " + rawAmount);
                    });
                }
        ));
        if (!shown) {
            player.sendMessage(plugin.tr("bank.usage"));
        }
    }

    private DialogApi api() {
        if (!plugin.config().getBoolean("interface.prefer-dialog", true)) return null;
        if (availabilityChecked) return dialogApi;

        synchronized (this) {
            if (availabilityChecked) return dialogApi;
            try {
                dialogApi = new DialogApi(plugin);
                plugin.logInfo("Native Paper/Leaf Dialog interface is available.");
            } catch (Throwable throwable) {
                dialogApi = null;
                plugin.logDebug("Native Dialog API is unavailable; inventory/chat fallback remains active: "
                        + rootMessage(throwable));
            } finally {
                availabilityChecked = true;
            }
            return dialogApi;
        }
    }

    private boolean showSafely(DialogOperation operation) {
        try {
            return operation.show();
        } catch (Throwable throwable) {
            if (failureLogged.compareAndSet(false, true)) {
                plugin.logWarning("Paper/Leaf Dialog UI failed and was disabled for this runtime; "
                        + "SotarPayments will use the inventory/chat fallback. Cause: " + rootMessage(throwable), throwable);
            }
            dialogApi = null;
            availabilityChecked = true;
            return false;
        }
    }

    private String displayTelco(String telco) {
        return CardProviderNames.displayName(telco);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface DialogOperation {
        boolean show() throws Exception;
    }

    @FunctionalInterface
    private interface DialogCallback {
        void accept(Object response, Player player);
    }

    private record DialogButton(String label, int width, DialogCallback callback) {
    }

    private record TextInput(String key, String label, int width, int maxLength) {
    }

    private static final class DialogApi {
        private final SotarPayments plugin;
        private final Class<?> componentClass;
        private final Class<?> actionButtonClass;
        private final Class<?> dialogActionClass;
        private final Class<?> dialogActionCallbackClass;
        private final Class<?> clickOptionsClass;
        private final Class<?> dialogBaseClass;
        private final Class<?> dialogTypeClass;
        private final Class<?> dialogClass;
        private final Object legacySerializer;
        private final Method deserializeMethod;

        private DialogApi(SotarPayments plugin) throws ReflectiveOperationException {
            this.plugin = plugin;
            ClassLoader loader = plugin.getClass().getClassLoader();
            this.componentClass = Class.forName("net.kyori.adventure.text.Component", false, loader);
            this.actionButtonClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.ActionButton", false, loader);
            this.dialogActionClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.action.DialogAction", false, loader);
            this.dialogActionCallbackClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.action.DialogActionCallback", false, loader);
            this.clickOptionsClass = Class.forName(
                    "net.kyori.adventure.text.event.ClickCallback$Options", false, loader);
            this.dialogBaseClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.DialogBase", false, loader);
            this.dialogTypeClass = Class.forName(
                    "io.papermc.paper.registry.data.dialog.type.DialogType", false, loader);
            this.dialogClass = Class.forName("io.papermc.paper.dialog.Dialog", false, loader);
            Class.forName("io.papermc.paper.dialog.DialogResponseView", false, loader);
            Class.forName("net.kyori.adventure.dialog.DialogLike", false, loader);

            Class<?> serializerClass = Class.forName(
                    "net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer", false, loader);
            Method legacySection = serializerClass.getMethod("legacySection");
            this.legacySerializer = legacySection.invoke(null);
            this.deserializeMethod = serializerClass.getMethod("deserialize", String.class);
        }

        private boolean showMultiAction(Player player,
                                        String title,
                                        String body,
                                        List<DialogButton> buttonDefinitions,
                                        int columns) throws Exception {
            List<Object> buttons = new ArrayList<>(buttonDefinitions.size());
            for (DialogButton definition : buttonDefinitions) {
                Object action = createAction(definition.callback());
                buttons.add(createButton(definition.label(), action, definition.width()));
            }

            Object dialog = createDialog(factory -> {
                try {
                    Object entryBuilder = invoke(factory, "empty");
                    Object base = createBase(title, body, List.of());
                    invoke(entryBuilder, "base", base);

                    Object typeBuilder = invokeStatic(dialogTypeClass, "multiAction", buttons);
                    Object exitButton = createButton(plugin.tr("dialog.cancel"), null, 150);
                    invoke(typeBuilder, "exitAction", exitButton);
                    invoke(typeBuilder, "columns", Math.max(1, Math.min(4, columns)));
                    Object type = invoke(typeBuilder, "build");
                    invoke(entryBuilder, "type", type);
                } catch (Exception exception) {
                    throw new DialogBuildException(exception);
                }
            });
            return show(player, dialog);
        }

        private boolean showConfirmation(Player player,
                                         String title,
                                         String body,
                                         List<TextInput> inputDefinitions,
                                         String confirmLabel,
                                         String cancelLabel,
                                         DialogCallback callback) throws Exception {
            List<Object> inputs = new ArrayList<>(inputDefinitions.size());
            for (TextInput input : inputDefinitions) {
                Object inputBuilder = invokeStatic(
                        Class.forName("io.papermc.paper.registry.data.dialog.input.DialogInput", false,
                                plugin.getClass().getClassLoader()),
                        "text", input.key(), component(input.label()));
                invoke(inputBuilder, "width", input.width());
                invoke(inputBuilder, "maxLength", input.maxLength());
                inputs.add(invoke(inputBuilder, "build"));
            }

            Object confirm = createButton(confirmLabel, createAction(callback), 150);
            Object cancel = createButton(cancelLabel, null, 150);
            Object confirmationType = invokeStatic(dialogTypeClass, "confirmation", confirm, cancel);

            Object dialog = createDialog(factory -> {
                try {
                    Object entryBuilder = invoke(factory, "empty");
                    Object base = createBase(title, body, inputs);
                    invoke(entryBuilder, "base", base);
                    invoke(entryBuilder, "type", confirmationType);
                } catch (Exception exception) {
                    throw new DialogBuildException(exception);
                }
            });
            return show(player, dialog);
        }

        private String readText(Object response, String key) {
            if (response == null || key == null) return "";
            try {
                Object value = invoke(response, "getText", key);
                return value == null ? "" : String.valueOf(value);
            } catch (Exception ignored) {
                return "";
            }
        }

        private Object createDialog(Consumer<Object> factoryConsumer) throws Exception {
            Method create = dialogClass.getMethod("create", Consumer.class);
            try {
                return create.invoke(null, factoryConsumer);
            } catch (ReflectiveOperationException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof DialogBuildException buildException
                        && buildException.getCause() instanceof Exception nested) {
                    throw nested;
                }
                throw exception;
            }
        }

        private Object createBase(String title, String body, List<Object> inputs) throws Exception {
            Object builder = invokeStatic(dialogBaseClass, "builder", component(title));
            invoke(builder, "canCloseWithEscape", true);

            if (body != null && !body.isBlank()) {
                Class<?> bodyClass = Class.forName(
                        "io.papermc.paper.registry.data.dialog.body.DialogBody", false,
                        plugin.getClass().getClassLoader());
                Object plainBody = invokeStatic(bodyClass, "plainMessage", component(body));
                invoke(builder, "body", List.of(plainBody));
            }
            if (inputs != null && !inputs.isEmpty()) {
                invoke(builder, "inputs", inputs);
            }
            return invoke(builder, "build");
        }

        private Object createButton(String label, Object action, int width) throws Exception {
            Method create = findMethod(actionButtonClass, "create", true, 4,
                    componentClass, componentClass, int.class, dialogActionClass);
            return create.invoke(null, component(label), null, Math.max(1, width), action);
        }

        private Object createAction(DialogCallback callback) throws Exception {
            if (callback == null) return null;

            InvocationHandler invocationHandler = (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "SotarPaymentsDialogCallback";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                        default -> null;
                    };
                }
                if (args != null && args.length >= 2 && args[1] instanceof Player player) {
                    callback.accept(args[0], player);
                }
                return null;
            };
            Object callbackProxy = Proxy.newProxyInstance(
                    dialogActionCallbackClass.getClassLoader(),
                    new Class<?>[]{dialogActionCallbackClass},
                    invocationHandler);

            Object optionsBuilder = invokeStatic(clickOptionsClass, "builder");
            invoke(optionsBuilder, "uses", 1);
            Object options = invoke(optionsBuilder, "build");
            Method customClick = findMethod(dialogActionClass, "customClick", true, 2,
                    dialogActionCallbackClass, clickOptionsClass);
            return customClick.invoke(null, callbackProxy, options);
        }

        private Object component(String legacyText) throws Exception {
            String text = legacyText == null ? "" : legacyText;
            String colored = ChatColor.translateAlternateColorCodes('&', text);
            return deserializeMethod.invoke(legacySerializer, colored);
        }

        private boolean show(Player player, Object dialog) throws Exception {
            if (player == null || dialog == null) return false;
            for (Method method : player.getClass().getMethods()) {
                if (!method.getName().equals("showDialog") || method.getParameterCount() != 1) continue;
                if (!method.getParameterTypes()[0].isInstance(dialog)) continue;
                method.invoke(player, dialog);
                return true;
            }
            return false;
        }

        private static Object invokeStatic(Class<?> type, String name, Object... arguments) throws Exception {
            Method method = findCompatibleMethod(type, name, true, arguments);
            return method.invoke(null, arguments);
        }

        private static Object invoke(Object target, String name, Object... arguments) throws Exception {
            if (target == null) throw new NoSuchMethodException(name + " on null target");
            Method method = findCompatibleMethod(target.getClass(), name, false, arguments);
            return method.invoke(target, arguments);
        }

        private static Method findCompatibleMethod(Class<?> type,
                                                   String name,
                                                   boolean requireStatic,
                                                   Object[] arguments) throws NoSuchMethodException {
            Method inaccessibleMatch = null;
            for (Method method : type.getMethods()) {
                if (!isCompatibleMethod(method, name, requireStatic, arguments)) continue;
                if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
                inaccessibleMatch = method;
            }

            // Adventure and Paper commonly return package-private builder
            // implementations. Invoking a public method declared by such an
            // implementation is rejected by newer Java runtimes. Resolve the
            // same method through its public API interface instead.
            if (!requireStatic && inaccessibleMatch != null) {
                Method publicDeclaration = findPublicInterfaceMethod(type, name, arguments);
                if (publicDeclaration != null) return publicDeclaration;
            }
            throw new NoSuchMethodException(type.getName() + "#" + name + "/" + arguments.length);
        }

        private static Method findPublicInterfaceMethod(Class<?> type,
                                                        String name,
                                                        Object[] arguments) {
            for (Class<?> interfaceType : type.getInterfaces()) {
                for (Method method : interfaceType.getMethods()) {
                    if (Modifier.isPublic(method.getDeclaringClass().getModifiers())
                            && isCompatibleMethod(method, name, false, arguments)) {
                        return method;
                    }
                }

                Method inherited = findPublicInterfaceMethod(interfaceType, name, arguments);
                if (inherited != null) return inherited;
            }

            Class<?> superclass = type.getSuperclass();
            return superclass == null
                    ? null
                    : findPublicInterfaceMethod(superclass, name, arguments);
        }

        private static boolean isCompatibleMethod(Method method,
                                                  String name,
                                                  boolean requireStatic,
                                                  Object[] arguments) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length) return false;
            if (requireStatic != Modifier.isStatic(method.getModifiers())) return false;

            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int index = 0; index < parameterTypes.length; index++) {
                if (!isCompatible(parameterTypes[index], arguments[index])) return false;
            }
            return true;
        }

        private static Method findMethod(Class<?> type,
                                         String name,
                                         boolean requireStatic,
                                         int parameterCount,
                                         Class<?>... expectedTypes) throws NoSuchMethodException {
            for (Method method : type.getMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != parameterCount) continue;
                if (requireStatic != Modifier.isStatic(method.getModifiers())) continue;
                Class<?>[] actual = method.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < actual.length && i < expectedTypes.length; i++) {
                    if (!wrap(actual[i]).isAssignableFrom(wrap(expectedTypes[i]))
                            && !wrap(expectedTypes[i]).isAssignableFrom(wrap(actual[i]))) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return method;
            }
            throw new NoSuchMethodException(type.getName() + "#" + name + "/" + parameterCount);
        }

        private static boolean isCompatible(Class<?> parameterType, Object argument) {
            if (argument == null) return !parameterType.isPrimitive();
            return wrap(parameterType).isInstance(argument);
        }

        private static Class<?> wrap(Class<?> type) {
            if (!type.isPrimitive()) return type;
            if (type == boolean.class) return Boolean.class;
            if (type == byte.class) return Byte.class;
            if (type == short.class) return Short.class;
            if (type == int.class) return Integer.class;
            if (type == long.class) return Long.class;
            if (type == float.class) return Float.class;
            if (type == double.class) return Double.class;
            if (type == char.class) return Character.class;
            return type;
        }
    }

    private static final class DialogBuildException extends RuntimeException {
        private DialogBuildException(Throwable cause) {
            super(cause);
        }
    }
}
