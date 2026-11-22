# Quest definitions for pack makers

The quests of the Folkhold villages come from quest definitions. A definition is a JSON file in a data pack. A data
pack of a world, of a server or of a mod jar can replace the definitions of Folkhold and add new definitions.

## Files

- A definition is the file `data/<namespace>/folkhold/quest/<path>.json`. Its id is `<namespace>:<path>`.
- A file with the path of a definition of Folkhold replaces this definition. For example,
  `data/folkhold_world/folkhold/quest/job_input_delivery.json` in your data pack replaces the delivery of the inputs
  of the jobs.
- A file with a new path adds a definition.
- `/reload` reads the files again. The new values apply to the next offers. The offers and the quests that exist do
  not change.
- A file with an error gives an error in the server log, with the id of the definition and the cause. The other files
  load. A field that this version of Folkhold does not use gives a warning in the log, and the file loads.

## Definitions of Folkhold

| Id | Kind | Need | Offers |
| --- | --- | --- | --- |
| `folkhold_world:job_input_delivery` | `delivery` | `job_inputs` | A village asks for an input of its active jobs with a low stock. |
| `folkhold_world:site_material_delivery` | `delivery` | `site_materials` | The builders ask for the materials of their sites. |
| `folkhold_world:caravan_escort` | `escort` | `caravan` | A new caravan waits for a player who protects it. |
| `folkhold_world:monster_hunt` | `hunt` | `monsters` | A village of the level 3 or more asks the player to kill monsters near it. |
| `folkhold_world:job_story_delivery` | `delivery` | `job_story` | A worker of an active job asks for an input of its job and gives a product of its job, with the story of its job. |

The values of the offers of these files come from the server config (`serverconfig/folkhold_world-server.toml` of
the world, section `quests`). The files give the reward rules:

| Id | Rule of the type | Special reward |
| --- | --- | --- |
| `folkhold_world:job_input_delivery` | 5 experience points | 25 %: half of the emeralds in goods of the village, and 10 experience points |
| `folkhold_world:site_material_delivery` | 10 experience points | none |
| `folkhold_world:caravan_escort` | 20 experience points | none |
| `folkhold_world:monster_hunt` | 15 experience points, 1 emerald for each monster (`reward.factor`) | none |
| `folkhold_world:job_story_delivery` | 10 experience points, all the value in products of the job of the giver (`goods_share` 1) | none |

The delivery of the inputs of the jobs also has start items: an iron pickaxe for 50 % of the deliveries of ores,
coal, redstone, cobblestone and flint.

## Format

```json
{
  "kind": "delivery",
  "need": "job_inputs",
  "enabled": true,
  "conditions": {"min_level": 1, "max_level": 20},
  "reward": {
    "factor": 1.5, "reputation": 20, "friend_reward": 64,
    "experience": 5,
    "items": [{"item": "minecraft:bread", "count": 2}],
    "goods_share": 0.0,
    "special": {"chance": 0.25, "factor": 1.5, "experience": 10, "items": [], "goods_share": 0.5}
  },
  "offer": {"offer_minutes": 20, "duration_minutes": 40, "max_count": 32},
  "start_items": {"chance": 0.5, "items": [{"item": "minecraft:iron_pickaxe"}], "targets": ["minecraft:raw_iron"]},
  "penalty": {"reputation": 10, "start_items_factor": 2.0}
}
```

| Field | Necessary | Default | Meaning |
| --- | --- | --- | --- |
| `kind` | yes | | `delivery`, `escort` or `hunt`. It must be the kind of the need. |
| `need` | yes | | What makes the offers: `job_inputs`, `job_story`, `site_materials` (kind `delivery`), `caravan` (kind `escort`) or `monsters` (kind `hunt`). |
| `enabled` | no | `true` | `false`: the definition makes no offer. |
| `conditions.min_level` | no | 1 | The lowest level of a village with offers of the definition. |
| `conditions.max_level` | no | no maximum | The highest level of a village with offers of the definition. |
| `reward.factor` | no | config | Delivery: the factor of the local price of the items. Escort: the share of the payment of the caravan. Hunt: the emeralds for each monster. |
| `reward.reputation` | no | config | The reputation of the reward. |
| `reward.friend_reward` | no | config | A quest with this reward in emeralds or more is only for the friend tier and the ally tier. |
| `reward.experience` | no | 0 | The experience points that the player gets. |
| `reward.items` | no | none | The items that the player gets: `item` (an item id) and `count` (1 by default). An unknown item id is an error. |
| `reward.goods_share` | no | 0 | The share of the emerald reward (0 to 1) that the village pays in its goods: the outputs of its active jobs in its stock. |
| `reward.special.chance` | no | 0 | The chance (0 to 1) that a new quest gets the special reward in the place of the rule of the type. |
| `reward.special.factor` | no | the factor of the definition | The factor of the emerald reward of a special reward. |
| `reward.special.experience`, `.items`, `.goods_share` | no | 0, none, 0 | The parts of the special reward, as above. |
| `start_items.items` | no | none | The items that the player gets when the quest starts. Same format as `reward.items`. |
| `start_items.chance` | no | 1 with items, else 0 | The chance (0 to 1) that a new quest gets the start items. |
| `start_items.targets` | no | all | The targets (item ids of a delivery) that get the start items. |
| `penalty.reputation` | no | config | The reputation that the player loses with the faction of the giver when the accepted quest fails. |
| `penalty.start_items_factor` | no | config | The extra loss for each emerald of the base value of the start items. |
| `texts.jobs` | no | none | Need `job_story`: the translation key of the story of each job id. The text gets the items of the quest as `%s`. |
| `texts.default` | no | none | Need `job_story`: the key of the story of a job with no key in `texts.jobs`. |
| `offer.offer_minutes` | no | config | Delivery and hunt: the time of the offer, in minutes. An escort offer lasts while the caravan waits. |
| `offer.duration_minutes` | no | config | Delivery and hunt: the time from the acceptance to the deadline. Escort: the minimum time of the escort. In minutes. |
| `offer.max_count` | no | config | Delivery: the maximum quantity of items. Hunt: the maximum number of monsters. |

## Rules of the offers

- A village makes the offers of the need `job_inputs` with all the matching definitions: one offer for each
  definition in turn, in the order of their ids. The number of offers of the village (1, plus 1 for each 5 levels)
  stays the maximum for all the definitions together. A village does not offer two deliveries of the same item.
- The needs `job_story`, `site_materials`, `caravan` and `monsters` use the first matching definition, in the order of
  the ids.
- A village offers one job quest at most. Its giver is an active job with an input and with an output in stock. Its
  goods are the outputs of the giver job only.
- A village offers one hunt at most. The number of monsters is 3, plus 1 for each 5 levels of the village.
- A village has at most `max(1, round(offers × maxNeedShare))` offers of one need (server config, 0.6). The hunt
  comes before the deliveries of the inputs of the jobs.
- A need with no matching definition makes no offer. Thus a file with `"enabled": false` at the path of a definition
  of Folkhold turns these quests off.
- Each quest keeps the id of its definition in the saved data.

## Rules of the rewards

- A reward has emeralds, reputation, experience points and items. The quest board shows each part before the player
  accepts.
- A new quest gets the special reward with its chance, else the rule of its type: the reward fields of the
  definition.
- The goods of a village are the item with the largest stock of the outputs of its active jobs. Their value at the
  local price replaces a part of the emeralds, and the emeralds stay at 1 or more. The quest takes the goods from the
  stock at the completion, up to the stock of this time.
- The items of `reward.items` do not come from the stock: the reward makes them.
- An item that does not go into the inventory of the player falls at the feet of the player.

## Rules of the failures

- An accepted quest that fails (abandoned, expired or failed) removes reputation with the faction and the village of
  the giver: `penalty.reputation` (config `failureReputation`, 10), plus the base value of the start items in emeralds
  × `penalty.start_items_factor` (config `startItemsPenalty`, 2), rounded. The board shows this loss on each offer and
  on each active quest.
- The player gets the start items when the quest starts, and keeps them after a success.
- A quest with start items is only for the friend tier and above. A player has 1 active quest with start items at
  most (config `maxStartItemQuests`).

## Limits of this version

- A definition cannot add a new kind or a new need. The kind and the need choose the code that makes the offers.
- Only the need `job_story` uses `texts`. A field that this version does not know gives a warning in the log.
- A definition cannot choose the items of a delivery: the need gives them.
