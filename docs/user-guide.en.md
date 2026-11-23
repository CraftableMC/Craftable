# User Guide — Craftable (Recipe Book)

[简体中文](user-guide.zh-CN.md) · [Documentation](README.md) · [Mod overview](../README.md)

Craftable uses materials available around you to prepare intermediate items recursively and finish ordinary vanilla crafting recipes. It builds on the vanilla Recipe Book without adding a storage network, new blocks, or a technology tree. The in-game feature is called **Recipe Book**; the name in the mod list is **Craftable**.

## Installation and compatibility

The available build is for **Minecraft 1.21.1 with NeoForge**, running on Java 21. Fabric and other versions are in the works.

Put the matching JAR in your game instance's `mods` folder and restart. For multiplayer, the server and clients need the same build. Remember to back up worlds and configuration before upgrading. If you'd like to build it yourself, have a look at the [Developer Guide](developer-guide.en.md).

## Getting started and controls

Put some ingredients in your main inventory or a nearby usable container, then open the Recipe Book in your inventory or at a crafting table.
Your inventory starts with 2×2 crafting. For 3×3, just place a usable crafting table nearby.

Hover over the recipe you want to make:

| Action | Result |
| --- | --- |
| Press C | Try one complete batch. For example, if a recipe makes four planks at once, you get all four. |
| Double-press C on the same recipe when materials are missing | Request partial preparation. The first press still tries a full craft, and you need to release the key between presses. One failed press or holding the key won't automatically use up some of the materials. |
| Shift+C | Open the item chain. Click the target or an intermediate item to choose a recipe, or adjust the final material and quantities/MAX. Click Craft when you're ready to make it. |
| Left-click | If you've learned the crafting recipe and it fits the current grid, fill it with carried materials or show a ghost pattern. Cooking recipes, unlearned recipes, and recipes that need a larger grid open details. Press C to use materials from nearby containers. |

The default crafting key is C, and you can change it in vanilla Controls. Shift follows the rebound key. The crafting key won't steal chat or search input.

Craftable removes the right-click popup from the enhanced Recipe Book. The recipe cards in details show the crafting pattern or the cooking input and result, along with the workstation you need.

Also, when the interface gets too narrow, vanilla's Recipe Book overlaps part of the inventory instead of sitting beside it. Craftable keeps that layout as it is.

### Recipe colors and corner symbols

Recipes use colors to show their state, with more detail in the tooltip. Here's the rough idea—these are descriptions of how the colors look, not exact color-chart names:

| Color | State | What it means |
| --- | --- | --- |
| Green | Craftable | The recipe currently shown can be crafted completely. |
| Red | Not craftable | The current target can't be crafted completely. Check the tooltip for the reason. |
| Pale yellow background | Partially craftable | Some materials are missing, but you can prepare some of the intermediate ingredients first. |
| Pale pinkish red | Another material in this button is craftable | Take fences, for example: the material currently shown isn't craftable, but another one in the same button is. You can wait for it to cycle around or choose it in details. |
| White / vanilla background | No conclusion | Craftability hasn't been worked out yet, or there's no usable result for now. |

The symbol in the top-right corner refers to the current target:

| Symbol | Meaning |
| --- | --- |
| `+` | You can craft one complete batch. |
| `!` | The current target isn't craftable; the tooltip explains why. |
| `~` | Partial preparation is possible, or the result is refreshing. |
| `?` | Craftability is still unresolved, possibly because this calculation reached its limit. |

You can still try crafting while a result is refreshing. The page may keep the last known color for a while; the server checks the materials, space, and rules again when you actually craft. Computing or unknown doesn't mean you're missing materials.

When several ingredient routes make the same item, Craftable can pick a workable route. For different output materials, such as boat or fence wood types, it makes the one currently shown or the one you chose. After changing a recipe or final material, take another look at the chain and quantities.

Items on your cursor and in the current 2×2/3×3 crafting grid count as materials too, so there's no need to put them back in your inventory first. Items held by the cursor or placed in the 2×2 crafting grid in the vanilla game do not count as materials you actually have; this has been fixed here, meaning it does not strictly adhere to the vanilla game's behavior.

Vanilla Spectator has a slightly surprising quirk: the Recipe Book may still be visible, even though you can't use it. Craftable follows vanilla here; its crafting ability isn't enabled in Creative or Spectator.

## Furnace processing

When a chain needs smelting or cooking, have the right furnace nearby, with its slots empty, and some fuel available. For example, you can start an iron pickaxe with raw iron when you're short on ingots.

You can also search for “Iron Ingot” in the inventory Recipe Book. Different ways to make the same item share a button. Left-click a furnace recipe to see its details, or press C to process it. The 2×2 inventory screen works with a nearby furnace. The top left of the details page shows the workstations available to you.

Lit, empty campfires and soul campfires can cook food using just the raw ingredients. With instant processing off, relight an extinguished campfire to resume cooking.

The world's **Instant processing** setting is on by default. If you turn it off, Craftable takes the inputs as soon as you submit. Processing follows the workstation and keeps going after you close the inventory or crafting table. Hover over the crafting arrow or the progress bar to see how long is left, why it paused, or whether the items are ready.

Processing pauses if the chunk stops ticking or you're using the workstation by hand. Once the items are ready, they go into your main inventory as soon as everything fits. If the workstation is broken before the job finishes, Craftable returns the inputs it took.

Wet sponges dry normally by default. To collect water as well, select **Dry sponge and collect water** from the sponge node in details. Use an empty bucket and fuel, or let one lava bucket supply the heat and collect the water.

## Stonecutting, smithing and brewing

Place the relevant workstation nearby and search the Recipe Book for stonecutting results, netherite gear or potions. Stonecutting and vanilla netherite upgrades complete instantly. Use the vanilla smithing table to apply trims or upgrade damaged, enchanted or renamed gear.

Brewing needs an idle stand. Craftable plans one bottle per step, with one reagent each time. One blaze powder pays for up to 20 steps in the same plan. Brewing follows the instant processing setting.

## Which materials can I use?

- Main inventory, the current 2×2/3×3 crafting grid, and the cursor stack.
- Nearby vanilla block containers that are loaded, in range, openable, and yours to use. The default range is 8 blocks horizontally and 4 vertically; world rules can change it.
- Placed, usable shulker boxes.
- Your own ender inventory, when world rules allow it and there's a placed, usable ender chest nearby.

Obstructions, locks, and ungenerated loot restrictions still apply.
Seeing one container through several views won't count its materials several times.
Items with non-default components, such as custom names or stored contents, are protected by default and won't be used up automatically by recursive crafting.

## What can't I use yet?

Some things are still in development. Others, like result slots and equipment, aren't ordinary ingredient sources in the first place:

- Items in the result slot, equipment slots, or offhand.
- Contents of carried shulker boxes, bundles, and similar item containers.
- Ender inventory access from an ender chest *item* in your inventory or nearby. For now, it needs to be placed as a block.
- Dropped items and entity inventories.
- Villager trading, entity interactions such as shearing or milking, and world interactions. For example, some mods turn one item into another when you put it in water. That sort of special processing can't plug in directly yet either.

The whitelist controls which supported recipes can skip confirmation.

## Quantity, space, and partial preparation

Simply put, the **root recipe** is the recipe for the thing you want at the end of the chain.

Quantity means how many times to run that recipe, which isn't always the number of items you get.
The default limit is 64 root executions per request, adjustable to 1–64 in world rules.
The whole chain also counts actual root and intermediate recipe executions separately: 128 by default, adjustable to 1–256.

MAX only shows an exact maximum once it's been worked out.
“At least” means we know you can make that many, and there may be more.
Raising the step limit doesn't add calculation time, memory, or transfer room, so complicated recipes can still have an unresolved maximum.

The output has to fit in your main inventory. Leftovers need to fit by default too; only when the world allows it and your personal settings don't forbid it can overflowing leftovers be dropped.

Partial preparation makes the fewest complete batches needed and allowed for the current target. It won't turn every last log into sticks while it's at it.
For example, a diamond pickaxe needs two sticks and you've only got one log. When allowed, preparation makes four planks, uses two of them to make four sticks, and leaves the rest of the planks alone. Space limits still apply.

### The four partial-crafting modes

World and personal settings offer the same four choices. They don't stop normal complete crafting:

| Mode | What it does to an explicit partial-preparation request |
| --- | --- |
| Disabled | Don't run a partial plan. |
| Confirm all | Let you view the partial plan, then ask you to confirm before making it. |
| Whitelist | Skip extra confirmation only when every actual step is on the world's whitelist. Anything outside the list needs confirmation. |
| No confirm | Skip extra confirmation for supported partial steps that pass validation. Other restrictions still apply. |

The world defaults to **Whitelist**.
The personal default, **No confirm**, just means “don't add another restriction.” It can't override the server's prohibition or confirmation requirement.
The stricter choice wins in this order: Disabled → Confirm all → Whitelist → No confirm.
Personal Whitelist mode uses the world's list too; there's no second personal list.

The whitelist stores full **recipe IDs**, not output item IDs, because one item can have more than one recipe. Sticks, for example, can be made from planks or bamboo.
The default list has 32 plank, stick, and selected storage-conversion recipes. In Advanced Configuration you edit the IDs directly: listed recipes skip extra confirmation; unlisted ones need it.
An empty list means all partial preparation needs confirmation. Switching modes doesn't delete the list.

The list holds up to 128 IDs, each up to 128 characters. Valid but unknown or unsupported IDs can stay there without taking effect yet.
If a data pack replaces a listed recipe with the same ID, that ID still decides whether confirmation is needed, but the real ingredients and yield follow the data pack. For example, if plank-to-stick yield changes from four to two, Craftable counts one batch as two planks making two sticks, not four.

Neither the whitelist nor No confirm bypasses unlocks, workstations, protected items, access permissions, space, or calculation limits.

## Settings

Find **Recipe Book** in the scrollable **Options → Accessibility Settings** list to open **Recipe Book Settings**.
The main page has six personal preferences: recipe-book enhancements, unlocked-only display, detailed failure feedback, partial crafting, dropping leftovers, and double-press interval.

### World rules and the whitelist

- In singleplayer, you can **Edit World Rules** once the world configuration has loaded, as long as you haven't opened to LAN.
- On a remote server or after opening LAN, the world-rule page is read-only—even for remote operators. Personal preferences are still editable, but can only tighten world rules.
- Without a world or loaded configuration, the ordinary world editor is unavailable. If you change worlds or open LAN while editing, the old page can't keep saving; go back and reopen it.
- **Read Rules Again** on the read-only page updates the rules read by that page. It isn't a refresh button for crafting previews.

Only Whitelist mode lets you edit **Partial Craft Whitelist**. The ordinary list can search item names, item IDs, or recipe IDs, then toggle **Allow direct prep** / **Require confirmation**. If there are too many results, narrow the search a little. Loading or temporary catalog trouble won't throw away your saved IDs.

For exact ID additions/removals or IDs that aren't recognized yet, use Craftable's configuration button in NeoForge **Mods**, or **Advanced**. Both screens edit the same settings and world list.

### Saving and cancelling settings

Yes, a section about Save and Cancel sounds a bit much. But Advanced Configuration makes it a little trickier, so let's go through it.

Ordinary editable pages change a draft first:

- **Done** saves; **Cancel** or Escape discards that page's draft.
- **Defaults** only resets the draft. You still need to click Done to save it.
- Done in the whitelist child page only goes back to the world-rule draft. Click Done on the parent as well; cancelling the parent drops the list edits too.
- Changes saved in Advanced take effect independently, so cancelling an ordinary page won't undo them. When you come back from Advanced, fields you've edited keep their draft values; fields you haven't touched pick up Advanced's new values.

After changing configuration, wait for the crafting result to refresh and take another look at the plan.

If that sounds a little complicated, remember this: to keep ordinary-page edits, click Done on each page as you go back.

## One more thing

The Recipe Book can show all synchronized ordinary recipes by default. If you'd rather only see unlocked recipes, turn on **Unlocked only** in personal settings. That's just a display filter, though; it won't unlock recipes for you.

To restrict crafting of locked recipes, use the world's recipe-unlock rule, `doLimitedCrafting`. Crafting follows that rule.

## Limitations and troubleshooting

| Symptom | Check first |
| --- | --- |
| Visible materials can't be used | Container range, loading, obstruction, and permissions; protected items; a usable workstation. |
| Still computing, or MAX only says “at least” | Try a smaller quantity first. Raising steps doesn't add calculation time. If it's simply stuck at the calculation limit, that's not your fault; I ([@lewe189](https://github.com/lewe189)) just haven't come up with a better way to calculate it yet. |
| A listed recipe still needs confirmation | Check the exact recipe ID, every actual step in the chain, and whether your personal mode is stricter. |
| World rules can't be edited | Check whether you've opened LAN, joined a remote server, haven't entered a world, or the configuration is still loading. |
| Settings don't seem to have saved | Check whether you clicked Done on the parent page. Advanced saves and ordinary-page Cancel are handled separately. |
| Something was craftable, but is now rejected or needs another look | Materials, space, rules, or permissions may have changed. Take another look at the current plan. |
| Can't join a server | Check game and loader versions, and make sure both sides have the same JAR. |

When reporting a problem, please include game and loader versions, JAR identity or hash, singleplayer/remote/LAN context, the target recipe and material, quantity, ingredient locations/counts, main-inventory space, what you did, and the relevant settings. Screenshots or timestamped logs help too. Leave out other players' private inventories and sensitive information.

See [Testing](../tests/README.md) for test groups and evidence boundaries, and the [development index](development/README.md) for designs and acceptance records. Before redistributing a modpack or modified version, remember to read the [license](../LICENSE) and its [Chinese translation](../LICENSE.zh-CN).
