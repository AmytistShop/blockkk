package me.hytaleblocker;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HytaleBlocker extends JavaPlugin implements Listener {

    private final Map<UUID, Long> blockUntilMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> attackLockUntilMs = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bedrockNextParryMs = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();

    // Config
    private boolean cfgEnabled;
    private boolean cfgUseFloodgate;
    private boolean cfgFreezeMovement;
    private boolean cfgBedrockSneakToBlock;

    private double cfgAngleDeg;
    private double cfgIncomingDamageMultiplier;

    private int cfgMinFoodLevel;
    private int cfgHungerOnBlock;
    private int cfgHungerOnParry;

    private int cfgDurabilityOnBlock;
    private int cfgDurabilityOnParry;

    private long cfgCancelAttackCooldownMs;

    private long cfgJavaRefreshWindowMs;
    private long cfgBedrockParryCooldownMs;

    private boolean cfgParryEnabled;
    private double cfgParryDamage;

    private final Set<Material> allowed = EnumSet.noneOf(Material.class);

    // Messages
    private String msgNoFood;
    private String msgAttackCooldown;
    private String msgReloaded;

    // Floodgate reflection
    private boolean floodgateAvailable = false;
    private Method floodgateGetInstance;
    private Method floodgateIsFloodgatePlayer;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadAll();

        Bukkit.getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("hytaleblocker")).setExecutor((sender, cmd, label, args) -> {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("hytaleblocker.reload")) {
                    sender.sendMessage(color("&cНет прав."));
                    return true;
                }
                reloadAll();
                sender.sendMessage(color(msgReloaded));
                return true;
            }
            sender.sendMessage(color("&e/hytaleblocker reload"));
            return true;
        });
    }

    private void reloadAll() {
        reloadConfig();
        FileConfiguration c = getConfig();

        cfgEnabled = c.getBoolean("block.enabled", true);
        cfgUseFloodgate = c.getBoolean("detect.use-floodgate", true);
        cfgFreezeMovement = c.getBoolean("block.freeze-movement", true);
        cfgBedrockSneakToBlock = c.getBoolean("bedrock.sneak-to-block", true);

        cfgAngleDeg = c.getDouble("block.angle-deg", 120.0);
        cfgIncomingDamageMultiplier = c.getDouble("block.incoming-damage-multiplier", 0.0);

        cfgMinFoodLevel = c.getInt("block.min-food-level", 1);
        cfgHungerOnBlock = c.getInt("block.hunger-cost-on-block", 1);
        cfgHungerOnParry = c.getInt("parry.hunger-cost-on-parry", 1);

        cfgDurabilityOnBlock = c.getInt("block.durability-loss-on-block", 3);
        cfgDurabilityOnParry = c.getInt("parry.durability-loss-on-parry", 1);

        cfgCancelAttackCooldownMs = c.getLong("block.cancel-attack-cooldown-ms", 1000);

        cfgJavaRefreshWindowMs = c.getLong("java.block-refresh-window-ms", 250);
        cfgBedrockParryCooldownMs = c.getLong("bedrock.parry-attack-cooldown-ms", 1000);

        cfgParryEnabled = c.getBoolean("parry.enabled", true);
        cfgParryDamage = c.getDouble("parry.damage", 3.0);

        msgNoFood = c.getString("messages.no-food", "&cНедостаточно голода для блока!");
        msgAttackCooldown = c.getString("messages.attack-cooldown", "&7Кулдаун: &c{seconds}s");
        msgReloaded = c.getString("messages.reloaded", "&aКонфиг перезагружен.");

        allowed.clear();
        for (String s : c.getStringList("allowed-items")) {
            if (s == null) continue;
            try {
                allowed.add(Material.valueOf(s.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {}
        }

        hookFloodgate();
    }

    private void hookFloodgate() {
        floodgateAvailable = false;
        floodgateGetInstance = null;
        floodgateIsFloodgatePlayer = null;

        if (!cfgUseFloodgate) return;

        try {
            Class<?> apiClz = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            floodgateGetInstance = apiClz.getMethod("getInstance");
            floodgateIsFloodgatePlayer = apiClz.getMethod("isFloodgatePlayer", UUID.class);
            floodgateAvailable = true;
            getLogger().info("Floodgate detected: Bedrock players will be recognized.");
        } catch (Throwable t) {
            getLogger().warning("Floodgate API not found. All players will be treated as Java.");
        }
    }

    private boolean isBedrock(Player p) {
        if (!cfgUseFloodgate || !floodgateAvailable) return false;
        try {
            Object api = floodgateGetInstance.invoke(null);
            Object res = floodgateIsFloodgatePlayer.invoke(api, p.getUniqueId());
            return res instanceof Boolean && (Boolean) res;
        } catch (Throwable t) {
            return false;
        }
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private boolean hasFood(Player p) {
        return p.getFoodLevel() >= cfgMinFoodLevel;
    }

    private boolean isAllowedWeapon(ItemStack item) {
        return item != null && item.getType() != Material.AIR && allowed.contains(item.getType());
    }

    private boolean isBlocking(Player p) {
        Long until = blockUntilMs.get(p.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    private void startOrRefreshBlock(Player p, long windowMs) {
        blockUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + windowMs);
        if (cfgFreezeMovement) frozen.add(p.getUniqueId());
    }

    private void endBlock(Player p) {
        blockUntilMs.remove(p.getUniqueId());
        frozen.remove(p.getUniqueId());
        attackLockUntilMs.put(p.getUniqueId(), System.currentTimeMillis() + cfgCancelAttackCooldownMs);
    }

    private boolean isInFrontCone(Player defender, Entity attacker) {
        if (attacker == null) return true;

        Vector look = defender.getLocation().getDirection().setY(0).normalize();
        Vector fromDefToAtt = attacker.getLocation().toVector().subtract(defender.getLocation().toVector()).setY(0).normalize();

        double dot = look.dot(fromDefToAtt);
        double half = Math.toRadians(cfgAngleDeg / 2.0);
        double threshold = Math.cos(half);
        return dot >= threshold;
    }

    private void damageItem(Player p, ItemStack item, int amount) {
        if (amount <= 0 || item == null) return;
        if (!(item.getItemMeta() instanceof Damageable dmg)) return;

        int max = item.getType().getMaxDurability();
        if (max <= 0) return;

        int newDamage = dmg.getDamage() + amount;
        dmg.setDamage(newDamage);
        item.setItemMeta(dmg);

        if (newDamage >= max) {
            p.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
        }
    }

    private void takeHunger(Player p, int foodPoints) {
        if (foodPoints <= 0) return;
        p.setFoodLevel(Math.max(0, p.getFoodLevel() - foodPoints));
    }

    private boolean isAttackLocked(Player p) {
        Long until = attackLockUntilMs.get(p.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    private void showCooldown(Player p) {
        long until = attackLockUntilMs.getOrDefault(p.getUniqueId(), 0L);
        double sec = Math.max(0.0, (until - System.currentTimeMillis()) / 1000.0);
        p.sendActionBar(color(msgAttackCooldown.replace("{seconds}", String.format(Locale.US, "%.1f", sec))));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent e) {
        if (!cfgEnabled) return;

        // Identify attacker player (melee or projectile)
        Player attackerPlayer = null;
        boolean arrow = false;
        boolean trident = false;

        if (e.getDamager() instanceof Player p) {
            attackerPlayer = p;
        } else if (e.getDamager() instanceof AbstractArrow a && a.getShooter() instanceof Player p) {
            attackerPlayer = p;
            arrow = true;
        } else if (e.getDamager() instanceof Trident t && t.getShooter() instanceof Player p) {
            attackerPlayer = p;
            trident = true;
        }

        if (attackerPlayer != null) {
            Player p = attackerPlayer;

            if (isAttackLocked(p)) {
                e.setCancelled(true);
                showCooldown(p);
                return;
            }

            // Check allowed weapon rules
            ItemStack weapon = p.getInventory().getItemInMainHand();

            if (arrow) {
                if (!(allowed.contains(Material.BOW) || allowed.contains(Material.CROSSBOW))) return;
            } else if (trident) {
                if (!allowed.contains(Material.TRIDENT)) return;
            } else {
                if (!isAllowedWeapon(weapon)) return;
            }

            boolean bedrock = isBedrock(p);

            // Bedrock: sneak+attack activates block and forces parry cooldown
            if (bedrock && cfgBedrockSneakToBlock && p.isSneaking()) {
                if (!hasFood(p)) {
                    e.setCancelled(true);
                    p.sendMessage(color(msgNoFood));
                    return;
                }

                long now = System.currentTimeMillis();
                long nextOk = bedrockNextParryMs.getOrDefault(p.getUniqueId(), 0L);
                if (now < nextOk) {
                    e.setCancelled(true);
                    showCooldown(p);
                    return;
                }

                startOrRefreshBlock(p, cfgJavaRefreshWindowMs);
                bedrockNextParryMs.put(p.getUniqueId(), now + cfgBedrockParryCooldownMs);

                if (cfgParryEnabled) {
                    e.setDamage(cfgParryDamage);
                    if (!arrow && !trident) {
                        damageItem(p, weapon, cfgDurabilityOnParry);
                    }
                    takeHunger(p, cfgHungerOnParry);
                } else {
                    e.setCancelled(true);
                }
                return;
            }

            // Java: each hit refreshes block window (hold LMB approximation)
            if (!bedrock) {
                if (hasFood(p)) {
                    startOrRefreshBlock(p, cfgJavaRefreshWindowMs);
                }

                if (cfgParryEnabled && isBlocking(p)) {
                    e.setDamage(cfgParryDamage);
                    if (!arrow && !trident) {
                        damageItem(p, weapon, cfgDurabilityOnParry);
                    }
                    takeHunger(p, cfgHungerOnParry);
                }
                return;
            }
        }

        // Incoming damage handling
        if (e.getEntity() instanceof Player victim) {
            Player p = victim;

            if (!isBlocking(p)) return;

            ItemStack weapon = p.getInventory().getItemInMainHand();
            if (!isAllowedWeapon(weapon)) {
                endBlock(p);
                return;
            }

            if (!hasFood(p)) {
                endBlock(p);
                p.sendMessage(color(msgNoFood));
                return;
            }

            if (!isInFrontCone(p, e.getDamager())) {
                return;
            }

            e.setDamage(Math.max(0.0, e.getDamage() * cfgIncomingDamageMultiplier));

            damageItem(p, weapon, cfgDurabilityOnBlock);
            takeHunger(p, cfgHungerOnBlock);

            if (!hasFood(p)) {
                endBlock(p);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!cfgEnabled || !cfgFreezeMovement) return;
        Player p = e.getPlayer();
        if (!frozen.contains(p.getUniqueId())) return;
        if (!isBlocking(p)) {
            frozen.remove(p.getUniqueId());
            return;
        }
        if (e.getFrom().getX() != e.getTo().getX()
                || e.getFrom().getZ() != e.getTo().getZ()
                || e.getFrom().getY() != e.getTo().getY()) {
            e.setTo(e.getFrom());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        blockUntilMs.remove(id);
        attackLockUntilMs.remove(id);
        bedrockNextParryMs.remove(id);
        frozen.remove(id);
    }
}
