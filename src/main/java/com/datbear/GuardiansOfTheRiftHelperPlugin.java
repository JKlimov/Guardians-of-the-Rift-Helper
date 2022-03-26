package com.datbear;

import com.google.common.collect.ImmutableSet;

import javax.inject.Inject;

import com.google.inject.Provides;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.Notifier;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
		name = "Guardians of the Rift Helper",
		description = "Show info about the Guardians of the Rift minigame",
		tags = {"minigame", "overlay", "guardians of the rift"}
)
public class GuardiansOfTheRiftHelperPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private GuardiansOfTheRiftHelperConfig config;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GuardiansOfTheRiftHelperOverlay overlay;

	@Inject
	private GuardiansOfTheRiftHelperPanel panel;

	@Inject
	private Notifier notifier;

	private static final int MINIGAME_MAIN_REGION = 14484;

	private static final Set<Integer> GUARDIAN_IDS = ImmutableSet.of(43705, 43701, 43710, 43702, 43703, 43711, 43704, 43708, 43712, 43707, 43706, 43709, 43702);
	private static final int GREAT_GUARDIAN_ID = 11403;

	private static final int CATALYTIC_GUARDIAN_STONE_ID = 26880;
	private static final int ELEMENTAL_GUARDIAN_STONE_ID = 26881;

	private static final int ELEMENTAL_ESSENCE_PILE_ID = 43722;
	private static final int CATALYTIC_ESSENCE_PILE_ID = 43723;

	private static final int UNCHARGED_CELL_ITEM_ID = 26882;
	private static final int UNCHARGED_CELL_GAMEOBJECT_ID = 43732;
	private static final int CHISEL_ID = 1755;
	private static final int OVERCHARGED_CELL_ID = 26886;

	private static final int GUARDIAN_ACTIVE_ANIM = 9363;

	private static final int CATALYTIC_RUNE_WIDGET_ID = 48889878;
	private static final int ELEMENTAL_RUNE_WIDGET_ID = 48889875;
	private static final int GUARDIAN_COUNT_WIDGET_ID = 48889885;
	private static final int PORTAL_WIDGET_ID = 48889883;

	private static final int PORTAL_ID = 43729;

	private static final int GET_REWARD_ANIMATION = 9353;

	private static final String REWARD_POINT_REGEX = "Elemental attunement level:[^>]+>(\\d+).*Catalytic attunement level:[^>]+>(\\d+)";
	private static final Pattern REWARD_POINT_PATTERN = Pattern.compile(REWARD_POINT_REGEX);

	@Getter(AccessLevel.PACKAGE)
	private final Set<GameObject> guardians = new HashSet<>();
	@Getter(AccessLevel.PACKAGE)
	private final Set<GameObject> activeGuardians = new HashSet<>();
	@Getter(AccessLevel.PACKAGE)
	private NPC greatGuardian;
	@Getter(AccessLevel.PACKAGE)
	private GameObject unchargedCellTable;
	@Getter(AccessLevel.PACKAGE)
	private GameObject catalyticEssencePile;
	@Getter(AccessLevel.PACKAGE)
	private GameObject elementalEssencePile;
	@Getter(AccessLevel.PACKAGE)
	private GameObject portal;

	@Getter(AccessLevel.PACKAGE)
	private boolean isInMinigame;
	@Getter(AccessLevel.PACKAGE)
	private boolean isInMainRegion;
	@Getter(AccessLevel.PACKAGE)
	private boolean outlineGreatGuardian = false;
	@Getter(AccessLevel.PACKAGE)
	private boolean outlineUnchargedCellTable = false;
	@Getter(AccessLevel.PACKAGE)
	private boolean shouldMakeGuardian = false;

	@Getter(AccessLevel.PACKAGE)
	private int elementalRewardPoints;
	@Getter(AccessLevel.PACKAGE)
	private int catalyticRewardPoints;

	@Getter(AccessLevel.PACKAGE)
	private Optional<Instant> portalSpawnTime = Optional.empty();
	@Getter(AccessLevel.PACKAGE)
	private Optional<Instant> lastPortalDespawnTime = Optional.empty();
	@Getter(AccessLevel.PACKAGE)
	private Optional<Instant> nextGameStart = Optional.empty();

	private String portalLocation = null;
	private int lastElementalRuneSprite;
	private int lastCatalyticRuneSprite;
	private boolean areGuardiansNeeded = false;

	private boolean checkInMinigame() {
		GameState gameState = client.getGameState();
		if (gameState != GameState.LOGGED_IN
				&& gameState != GameState.LOADING)
		{
			return false;
		}

		Widget elementalRuneWidget = client.getWidget(ELEMENTAL_RUNE_WIDGET_ID);
		return elementalRuneWidget != null;
	}

	private boolean checkInMainRegion(){
		int[] currentMapRegions = client.getMapRegions();
		return Arrays.stream(currentMapRegions).anyMatch(x -> x == MINIGAME_MAIN_REGION);
	}

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(panel);
		isInMinigame = true;
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		overlayManager.remove(panel);
		reset();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getItemContainer() != client.getItemContainer(InventoryID.INVENTORY))
		{
			return;
		}

		if(!isInMinigame) return;

		Item[] items = event.getItemContainer().getItems();
		if(Arrays.stream(items).anyMatch(x -> x.getId() == ELEMENTAL_GUARDIAN_STONE_ID || x.getId() == CATALYTIC_GUARDIAN_STONE_ID)){
			outlineGreatGuardian = true;
		} else {
			outlineGreatGuardian = false;
		}

		if(Arrays.stream(items).noneMatch(x -> x.getId() == UNCHARGED_CELL_ITEM_ID)) {
			outlineUnchargedCellTable = true;
		} else {
			outlineUnchargedCellTable = false;
		}

		if(Arrays.stream(items).anyMatch(x -> x.getId() == CHISEL_ID) && Arrays.stream(items).anyMatch(x -> x.getId() == OVERCHARGED_CELL_ID) && areGuardiansNeeded) {
			shouldMakeGuardian = true;
		} else {
			shouldMakeGuardian = false;
		}

	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		isInMinigame = checkInMinigame();
		isInMainRegion = checkInMainRegion();

		activeGuardians.removeIf(ag -> {
			Animation anim = ((DynamicObject)ag.getRenderable()).getAnimation();
			return anim == null || anim.getId() != GUARDIAN_ACTIVE_ANIM;
		});

		for(GameObject guardian : guardians){
			Animation animation = ((DynamicObject) guardian.getRenderable()).getAnimation();
			if(animation != null && animation.getId() == GUARDIAN_ACTIVE_ANIM) {
				activeGuardians.add(guardian);
			}
		}

		Widget elementalRuneWidget = client.getWidget(ELEMENTAL_RUNE_WIDGET_ID);
		Widget catalyticRuneWidget = client.getWidget(CATALYTIC_RUNE_WIDGET_ID);
		Widget guardianCountWidget = client.getWidget(GUARDIAN_COUNT_WIDGET_ID);
		Widget portalWidget = client.getWidget(PORTAL_WIDGET_ID);

		lastElementalRuneSprite = parseRuneWidget(elementalRuneWidget, lastElementalRuneSprite);
		lastCatalyticRuneSprite = parseRuneWidget(catalyticRuneWidget, lastCatalyticRuneSprite);

		if(guardianCountWidget != null) {
			String text = guardianCountWidget.getText();
			areGuardiansNeeded = text != null && !text.contains("10/10");
		}

		if(portalWidget != null && !portalWidget.isHidden()){
			if(!portalSpawnTime.isPresent() && lastPortalDespawnTime.isPresent()) {
				lastPortalDespawnTime = Optional.empty();
				if(config.notifyPortalSpawn()){
					notifier.notify("A portal has spawned in the " + portalWidget.getText() + ".");
				}
			}
			portalLocation = portalWidget.getText();
			portalSpawnTime = portalSpawnTime.isPresent() ? portalSpawnTime : Optional.of(Instant.now());
		} else if(elementalRuneWidget != null && !elementalRuneWidget.isHidden()) {
			if(portalSpawnTime.isPresent()){
				lastPortalDespawnTime = Optional.of(Instant.now());
			}
			portalLocation = null;
			portalSpawnTime = Optional.empty();
		}
	}

	int parseRuneWidget(Widget runeWidget, int lastSpriteId){
		if(runeWidget != null) {
			int spriteId = runeWidget.getSpriteId();
			if(spriteId != lastSpriteId) {
				if(lastSpriteId > 0) {
					Optional<GuardianInfo> lastGuardian = GuardianInfo.ALL.stream().filter(g -> g.spriteId == lastSpriteId).findFirst();
					if(lastGuardian.isPresent()) {
						lastGuardian.get().despawn();
					}
				}

				Optional<GuardianInfo> currentGuardian = GuardianInfo.ALL.stream().filter(g -> g.spriteId == spriteId).findFirst();
				if(currentGuardian.isPresent()) {
					currentGuardian.get().spawn();
				}
			}

			return spriteId;
		}
		return lastSpriteId;
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		GameObject gameObject = event.getGameObject();
		if(GUARDIAN_IDS.contains(event.getGameObject().getId())) {
			guardians.removeIf(g -> g.getId() == gameObject.getId());
			activeGuardians.removeIf(g -> g.getId() == gameObject.getId());
			guardians.add(gameObject);
		}

		if(gameObject.getId() == UNCHARGED_CELL_GAMEOBJECT_ID){
			unchargedCellTable = gameObject;
		}

		if(gameObject.getId() == ELEMENTAL_ESSENCE_PILE_ID){
			elementalEssencePile = gameObject;
		}

		if(gameObject.getId() == CATALYTIC_ESSENCE_PILE_ID){
			catalyticEssencePile = gameObject;
		}

		if(gameObject.getId() == PORTAL_ID){
			log.info("portal gameobject spawned");
			portal = gameObject;
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned npcSpawned) {
		NPC npc = npcSpawned.getNpc();
		if(npc.getId() == GREAT_GUARDIAN_ID){
			greatGuardian = npc;
		}
	}


	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING)
		{
			// on region changes the tiles get set to null
			reset();
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			// Prevent code from running while logged out.
			isInMinigame = false;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		if(!isInMainRegion) return;
		if(chatMessage.getType() != ChatMessageType.SPAM && chatMessage.getType() != ChatMessageType.GAMEMESSAGE) return;

		String msg = chatMessage.getMessage();
		if(msg.contains("The rift becomes active!")) {
			lastPortalDespawnTime = Optional.of(Instant.now());
			nextGameStart = Optional.empty();
		} else if(msg.contains("The rift will become active in 30 seconds.")) {
			nextGameStart = Optional.of(Instant.now().plusSeconds(30));
		} else if(msg.contains("The rift will become active in 10 seconds.")) {
			nextGameStart = Optional.of(Instant.now().plusSeconds(10));
		} else if(msg.contains("The rift will become active in 5 seconds.")) {
			nextGameStart = Optional.of(Instant.now().plusSeconds(5));
		} else if(msg.contains("The Portal Guardians will keep their rifts open for another 30 seconds.")){
			nextGameStart = Optional.of(Instant.now().plusSeconds(60));
		}

		Matcher rewardPointMatcher = REWARD_POINT_PATTERN.matcher(msg);
		if(rewardPointMatcher.find()) {
			elementalRewardPoints = Integer.parseInt(rewardPointMatcher.group(1));
			catalyticRewardPoints = Integer.parseInt(rewardPointMatcher.group(2));
			log.info("elemental points: " + elementalRewardPoints + " catalytic points: " + catalyticRewardPoints);
		}
		log.info(msg);
	}

	private void reset() {
		guardians.clear();
		activeGuardians.clear();
		unchargedCellTable = null;
		greatGuardian = null;
		catalyticEssencePile = null;
		elementalEssencePile = null;
	}

	@Provides
	GuardiansOfTheRiftHelperConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GuardiansOfTheRiftHelperConfig.class);
	}
}