package vn.sotarpayments.common.manager;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

public class MenuHolder implements InventoryHolder {
    private final String menuId;
    private final Map<String, String> data = new HashMap<>();
    private final Map<Integer, String> slotValues = new HashMap<>();
    private final Map<Integer, ActionBinding> slotActions = new HashMap<>();

    public MenuHolder(String menuId) {
        this.menuId = menuId;
    }

    public String getMenuId() {
        return menuId;
    }

    public void setData(String key, String value) {
        if (key == null) return;
        if (value == null) {
            data.remove(key);
            return;
        }
        data.put(key, value);
    }

    public String getData(String key) {
        return data.get(key);
    }

    public void setSlotValue(int slot, String value) {
        if (value == null) {
            slotValues.remove(slot);
            return;
        }
        slotValues.put(slot, value);
    }

    public String getSlotValue(int slot) {
        return slotValues.get(slot);
    }

    public void bindAction(int slot, MenuAction action, String payload) {
        if (slot < 0 || action == null || action == MenuAction.NONE) {
            slotActions.remove(slot);
            return;
        }
        slotActions.put(slot, new ActionBinding(action, payload));
    }

    public ActionBinding getAction(int slot) {
        return slotActions.get(slot);
    }

    @Override
    public Inventory getInventory() {
        return null;
    }

    public record ActionBinding(MenuAction action, String payload) {}
}
