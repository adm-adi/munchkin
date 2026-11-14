package com.munchkin.app.network

import com.munchkin.app.core.ApplyMode
import com.munchkin.app.core.BonusTarget
import com.munchkin.app.core.CharacterClass
import com.munchkin.app.core.CharacterRace
import com.munchkin.app.core.CombatAddBonus
import com.munchkin.app.core.CombatAddMonster
import com.munchkin.app.core.CombatEnd
import com.munchkin.app.core.CombatOutcome
import com.munchkin.app.core.CombatStart
import com.munchkin.app.core.CombatState
import com.munchkin.app.core.CombatUpdateMonster
import com.munchkin.app.core.ConditionType
import com.munchkin.app.core.ConditionalModifier
import com.munchkin.app.core.DiceRollPurpose
import com.munchkin.app.core.GameEnd
import com.munchkin.app.core.GameId
import com.munchkin.app.core.GamePhase
import com.munchkin.app.core.GameSettings
import com.munchkin.app.core.GameState
import com.munchkin.app.core.Gender
import com.munchkin.app.core.IncLevel
import com.munchkin.app.core.ModifierScope
import com.munchkin.app.core.ModifierSide
import com.munchkin.app.core.MonsterInstance
import com.munchkin.app.core.PlayerId
import com.munchkin.app.core.PlayerMeta
import com.munchkin.app.core.PlayerRoll
import com.munchkin.app.core.PlayerState
import com.munchkin.app.core.SetClass
import com.munchkin.app.core.SetGear
import com.munchkin.app.core.SetRace
import com.munchkin.app.core.TempBonus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests verifying strict kotlinx.serialization parity against realistic
 * server JSON payloads using the exact [Json] settings from [GameClient].
 */
class ProtocolSerializationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
        coerceInputValues = true
    }

    @Test
    fun `decodes realistic server WELCOME and STATE_SNAPSHOT payloads with unknown keys`() {
        val welcomeJson = """
            {
              "type": "WELCOME",
              "yourPlayerId": "p1",
              "reconnectToken": "rc-tok-123",
              "gameState": {
                "gameId": "game-42",
                "joinCode": "ABCD2345",
                "epoch": 2,
                "seq": 14,
                "hostId": "p1",
                "originalHostId": "p1",
                "phase": "IN_GAME",
                "winnerId": null,
                "turnPlayerId": "p1",
                "turnEndsAt": 1700000050000,
                "playerOrder": ["p1", "p2"],
                "createdAt": 1700000000000,
                "settings": {
                  "minLevel": 1,
                  "maxLevel": 10,
                  "tiesGoToMonsters": true,
                  "levelTenOnlyCombat": true,
                  "allowLevelTenOverride": false,
                  "turnTimerSeconds": 60
                },
                "players": {
                  "p1": {
                    "playerId": "p1",
                    "name": "Hero",
                    "avatarId": 3,
                    "gender": "F",
                    "characterClass": "WARRIOR",
                    "characterRace": "ELF",
                    "secondaryClass": "CLERIC",
                    "secondaryRace": "HUMAN",
                    "level": 6,
                    "gearBonus": 5,
                    "tempCombatBonus": 2,
                    "treasures": 4,
                    "hasHalfBreed": false,
                    "hasSuperMunchkin": true,
                    "isConnected": true,
                    "lastRoll": 6,
                    "reachedMaxLevelViaCombat": false
                  },
                  "p2": {
                    "playerId": "p2",
                    "name": "Ally",
                    "avatarId": 1,
                    "gender": "M",
                    "characterClass": "NONE",
                    "characterRace": "DWARF",
                    "secondaryClass": "NONE",
                    "secondaryRace": "HUMAN",
                    "level": 4,
                    "gearBonus": 3,
                    "tempCombatBonus": 0,
                    "treasures": 1,
                    "hasHalfBreed": false,
                    "hasSuperMunchkin": false,
                    "isConnected": true,
                    "lastRoll": 2,
                    "reachedMaxLevelViaCombat": false
                  }
                },
                "combat": {
                  "mainPlayerId": "p1",
                  "helperPlayerId": "p2",
                  "heroModifier": 1,
                  "monsterModifier": -2,
                  "isActive": true,
                  "monsters": [
                    {
                      "id": "m1",
                      "name": "Wight Brothers",
                      "baseLevel": 16,
                      "flatModifier": 2,
                      "treasures": 4,
                      "levels": 2,
                      "isUndead": true,
                      "badStuff": "Lose 2 levels",
                      "conditionalModifiers": [
                        {
                          "id": "cm1",
                          "amount": 3,
                          "side": "MONSTER",
                          "conditionType": "RACE_ID",
                          "conditionValue": "ELF",
                          "scope": "ANY_PARTICIPANT",
                          "applyMode": "ONCE_IF_MATCH"
                        }
                      ]
                    }
                  ],
                  "tempBonuses": [
                    { "id": "b1", "label": "Potion", "amount": 3, "appliesTo": "HEROES" }
                  ],
                  "lastDiceRoll": {
                    "playerId": "p1",
                    "playerName": "Hero",
                    "result": 5,
                    "success": true,
                    "timestamp": 1700000010000,
                    "purpose": "RUN_AWAY"
                  }
                }
              }
            }
        """.trimIndent()

        val welcome = json.decodeFromString<WsMessage>(welcomeJson) as WelcomeMessage
        assertEquals(PlayerId("p1"), welcome.yourPlayerId)
        assertEquals("rc-tok-123", welcome.reconnectToken)
        assertEquals(GameId("game-42"), welcome.gameState.gameId)
        assertEquals("ABCD2345", welcome.gameState.joinCode)
        assertEquals(GamePhase.IN_GAME, welcome.gameState.phase)
        assertEquals(60, welcome.gameState.settings.turnTimerSeconds)
        assertTrue(welcome.gameState.players[PlayerId("p1")]!!.hasSuperMunchkin)
        val combat = welcome.gameState.combat
        assertNotNull(combat)
        assertEquals(PlayerId("p2"), combat!!.helperPlayerId)
        assertEquals(1, combat.monsters.size)
        assertEquals(ConditionType.RACE_ID, combat.monsters.first().conditionalModifiers.first().conditionType)
        assertEquals(DiceRollPurpose.RUN_AWAY, combat.lastDiceRoll!!.purpose)

        val snapshotJson = """
            {
              "type": "STATE_SNAPSHOT",
              "seq": 15,
              "gameState": ${json.encodeToString(welcome.gameState)}
            }
        """.trimIndent()
        val snapshot = json.decodeFromString<WsMessage>(snapshotJson) as StateSnapshotMessage
        assertEquals(15L, snapshot.seq)
        assertEquals(GameId("game-42"), snapshot.gameState.gameId)
    }

    @Test
    fun `decodes and encodes EVENT_BROADCAST covering gameplay combat dice and game end events`() {
        val payloads = listOf(
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":1,"event":{"type":"INC_LEVEL","eventId":"e1","actorId":"p1","timestamp":1000,"targetPlayerId":"p1","amount":2,"reason":"Treasure"}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":2,"event":{"type":"SET_GEAR","eventId":"e2","actorId":"p1","timestamp":1001,"targetPlayerId":"p1","gear":7}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":3,"event":{"type":"SET_CLASS","eventId":"e3","actorId":"p1","timestamp":1002,"targetPlayerId":"p1","newClass":"WARRIOR","isSecondary":true}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":4,"event":{"type":"SET_RACE","eventId":"e4","actorId":"p1","timestamp":1003,"targetPlayerId":"p1","newRace":"ELF","isSecondary":true}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":5,"event":{"type":"COMBAT_START","eventId":"e5","actorId":"p1","timestamp":1004,"targetPlayerId":null,"mainPlayerId":"p1"}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":6,"event":{"type":"COMBAT_ADD_MONSTER","eventId":"e6","actorId":"p1","timestamp":1005,"targetPlayerId":null,"monster":{"id":"m1","name":"Orc","baseLevel":4,"flatModifier":0,"treasures":2,"levels":1,"isUndead":false,"badStuff":"Lose level","conditionalModifiers":[]}}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":7,"event":{"type":"COMBAT_UPDATE_MONSTER","eventId":"e7","actorId":"p1","timestamp":1006,"targetPlayerId":null,"monster":{"id":"m1","name":"Orc","baseLevel":4,"flatModifier":5,"treasures":2,"levels":1,"isUndead":false,"badStuff":"Lose level","conditionalModifiers":[]}}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":8,"event":{"type":"COMBAT_ADD_BONUS","eventId":"e8","actorId":"p1","timestamp":1007,"targetPlayerId":null,"bonus":{"id":"b1","label":"Potion","amount":3,"appliesTo":"HEROES"}}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":9,"event":{"type":"COMBAT_END","eventId":"e9","actorId":"p1","timestamp":1008,"targetPlayerId":null,"outcome":"WIN","levelsGained":1,"treasuresGained":2,"helperLevelsGained":1,"expectedSeq":8,"cancelled":true}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":10,"event":{"type":"PLAYER_ROLL","eventId":"e10","actorId":"p1","timestamp":1009,"targetPlayerId":"p1","result":6,"purpose":"RUN_AWAY","success":true}}""",
            """{"type":"EVENT_BROADCAST","gameId":"g1","epoch":1,"seq":11,"event":{"type":"GAME_END","eventId":"e11","actorId":"p1","timestamp":1010,"targetPlayerId":null,"winnerId":"p1"}}"""
        )

        val decodedEvents = payloads.map { raw ->
            val broadcast = json.decodeFromString<WsMessage>(raw) as EventBroadcastMessage
            broadcast.event
        }

        assertEquals(11, decodedEvents.size)
        val incLevel = decodedEvents[0] as IncLevel
        assertEquals(2, incLevel.amount)
        assertEquals("Treasure", incLevel.reason)

        assertEquals(7, (decodedEvents[1] as SetGear).gear)

        val setClass = decodedEvents[2] as SetClass
        assertEquals(CharacterClass.WARRIOR, setClass.newClass)
        assertTrue(setClass.isSecondary)

        val setRace = decodedEvents[3] as SetRace
        assertEquals(CharacterRace.ELF, setRace.newRace)
        assertTrue(setRace.isSecondary)

        assertEquals(PlayerId("p1"), (decodedEvents[4] as CombatStart).mainPlayerId)
        assertEquals("Orc", (decodedEvents[5] as CombatAddMonster).monster.name)
        assertEquals(5, (decodedEvents[6] as CombatUpdateMonster).monster.flatModifier)

        val addBonus = decodedEvents[7] as CombatAddBonus
        assertEquals(BonusTarget.HEROES, addBonus.bonus.appliesTo)
        assertEquals(3, addBonus.bonus.amount)

        val combatEnd = decodedEvents[8] as CombatEnd
        assertEquals(CombatOutcome.WIN, combatEnd.outcome)
        assertEquals(8L, combatEnd.expectedSeq)
        assertTrue(combatEnd.cancelled)
        assertEquals(1, combatEnd.helperLevelsGained)

        val roll = decodedEvents[9] as PlayerRoll
        assertEquals(6, roll.result)
        assertEquals(DiceRollPurpose.RUN_AWAY, roll.purpose)
        assertTrue(roll.success)

        val gameEnd = decodedEvents[10] as GameEnd
        assertEquals(PlayerId("p1"), gameEnd.winnerId)

        // Verify EventRequestMessage round-trip preserves event discriminator and bare IDs
        val encodedReq = json.encodeToString<WsMessage>(EventRequestMessage(combatEnd))
        val parsedBack = json.decodeFromString<WsMessage>(encodedReq) as EventRequestMessage
        assertEquals(combatEnd, parsedBack.event)
        assertTrue(encodedReq.contains("\"type\":\"EVENT_REQUEST\""))
        assertTrue(encodedReq.contains("\"type\":\"COMBAT_END\""))
    }

    @Test
    fun `decodes AUTH_SUCCESS HISTORY_RESULT LEADERBOARD_RESULT HOSTED_GAMES_RESULT GAMES_LIST and CATALOG_SEARCH_RESULT`() {
        val authJson = """
            {
              "type": "AUTH_SUCCESS",
              "user": {
                "id": "u-1",
                "username": "MunchkinMaster",
                "email": "hero@dungeon.test",
                "avatarId": 4,
                "gender": "F"
              },
              "token": "jwt.header.sig"
            }
        """.trimIndent()
        val auth = json.decodeFromString<WsMessage>(authJson) as AuthSuccessMessage
        assertEquals("u-1", auth.user.id)
        assertEquals(Gender.F, auth.user.gender)
        assertEquals("jwt.header.sig", auth.token)

        val historyJson = """
            {
              "type": "HISTORY_RESULT",
              "stats": { "wins": 3, "gamesPlayed": 6 },
              "games": [
                {
                  "id": "g-hist-1",
                  "endedAt": 1700001111000,
                  "winnerId": "u-1",
                  "winnerName": "MunchkinMaster",
                  "didIWin": true,
                  "playerCount": 4
                }
              ]
            }
        """.trimIndent()
        val history = json.decodeFromString<WsMessage>(historyJson) as HistoryResult
        assertEquals(50, history.stats.winRate)
        assertEquals(1, history.games.size)
        assertTrue(history.games.first().didIWin)

        val leaderboardJson = """
            {
              "type": "LEADERBOARD_RESULT",
              "leaderboard": [
                {
                  "id": "u-1",
                  "username": "MunchkinMaster",
                  "avatarId": 4,
                  "gender": "F",
                  "wins": 3,
                  "gamesPlayed": 6
                }
              ],
              "me": { "wins": 3, "gamesPlayed": 6, "rank": 1 }
            }
        """.trimIndent()
        val leaderboard = json.decodeFromString<WsMessage>(leaderboardJson) as LeaderboardResult
        assertEquals(50, leaderboard.leaderboard.first().winRate)
        assertEquals(1, leaderboard.me!!.rank)

        val hostedGamesJson = """
            {
              "type": "HOSTED_GAMES_RESULT",
              "games": [
                {
                  "gameId": "g-99",
                  "joinCode": "HOST99",
                  "playerCount": 3,
                  "phase": "LOBBY",
                  "createdAt": 1700002222000
                }
              ]
            }
        """.trimIndent()
        val hosted = json.decodeFromString<WsMessage>(hostedGamesJson) as HostedGamesResult
        assertEquals("HOST99", hosted.games.single().joinCode)

        val gamesListJson = """
            {
              "type": "GAMES_LIST",
              "games": [
                {
                  "hostName": "Ana",
                  "joinCode": "ROOM1234",
                  "playerCount": 2,
                  "maxPlayers": 6
                }
              ]
            }
        """.trimIndent()
        val gamesList = json.decodeFromString<WsMessage>(gamesListJson) as GameListMessage
        assertEquals("ROOM1234", gamesList.games.single().joinCode)

        val catalogJson = """
            {
              "type": "CATALOG_SEARCH_RESULT",
              "results": [
                {
                  "id": "mon-1",
                  "name": "Plutonium Dragon",
                  "level": 20,
                  "modifier": 0,
                  "treasures": 5,
                  "levels": 2,
                  "isUndead": false,
                  "badStuff": "You are dead.",
                  "expansion": "base",
                  "createdBy": null
                }
              ]
            }
        """.trimIndent()
        val catalog = json.decodeFromString<WsMessage>(catalogJson) as CatalogSearchResult
        assertEquals(20, catalog.results.single().level)
        assertEquals(5, catalog.results.single().treasures)
    }

    @Test
    fun `unknown ErrorCode string deserializes to ErrorCode UNKNOWN`() {
        val raw = """{"type":"ERROR","code":"BRAND_NEW_SERVER_ERROR","message":"Upgrade client"}"""
        val decoded = json.decodeFromString<WsMessage>(raw) as ErrorMessage

        assertEquals(ErrorCode.UNKNOWN, decoded.code)
        assertEquals("Upgrade client", decoded.message)

        val knownRaw = """{"type":"ERROR","code":"COMBAT_MONSTER_LIMIT","message":"Too many monsters"}"""
        val knownDecoded = json.decodeFromString<WsMessage>(knownRaw) as ErrorMessage
        assertEquals(ErrorCode.COMBAT_MONSTER_LIMIT, knownDecoded.code)
    }

    @Test
    fun `serializable object singletons serialize with type discriminator`() {
        val cases = listOf(
            GetLeaderboardRequest to "GET_LEADERBOARD",
            GetHostedGamesRequest to "GET_HOSTED_GAMES",
            ListGamesRequest to "LIST_GAMES",
            AccountDeletedMessage to "ACCOUNT_DELETED"
        )

        cases.forEach { (singleton, expectedType) ->
            val encoded = json.encodeToString<WsMessage>(singleton)
            val parsedJson = json.parseToJsonElement(encoded).jsonObject
            assertEquals(expectedType, parsedJson["type"]?.jsonPrimitive?.content)

            val decoded = json.decodeFromString<WsMessage>(encoded)
            assertSame(singleton, decoded)
        }
    }

    @Test
    fun `value class GameId and PlayerId serialize as bare strings`() {
        val statusMsg = PlayerStatusMessage(playerId = PlayerId("player-abc"), isConnected = false)
        val statusJson = json.encodeToString<WsMessage>(statusMsg)
        assertTrue(statusJson.contains("\"playerId\":\"player-abc\""))
        assertFalse(statusJson.contains("\"value\""))

        val gameState = GameState(
            gameId = GameId("game-xyz"),
            joinCode = "CODE88",
            hostId = PlayerId("player-abc"),
            winnerId = PlayerId("player-abc"),
            turnPlayerId = PlayerId("player-abc"),
            playerOrder = listOf(PlayerId("player-abc")),
            players = mapOf(
                PlayerId("player-abc") to PlayerState(
                    playerId = PlayerId("player-abc"),
                    name = "Hero"
                )
            ),
            combat = CombatState(
                mainPlayerId = PlayerId("player-abc"),
                monsters = listOf(
                    MonsterInstance(
                        id = "m1",
                        name = "Slime",
                        baseLevel = 1,
                        conditionalModifiers = listOf(
                            ConditionalModifier(
                                id = "c1",
                                amount = 2,
                                side = ModifierSide.MONSTER,
                                conditionType = ConditionType.CLASS_ID,
                                conditionValue = "WARRIOR",
                                scope = ModifierScope.MAIN_ONLY,
                                applyMode = ApplyMode.ONCE_IF_MATCH
                            )
                        )
                    )
                ),
                tempBonuses = listOf(TempBonus("t1", "Torch", 2, BonusTarget.HEROES))
            ),
            settings = GameSettings()
        )
        val stateJson = json.encodeToString(gameState)
        assertTrue(stateJson.contains("\"gameId\":\"game-xyz\""))
        assertTrue(stateJson.contains("\"hostId\":\"player-abc\""))
        assertFalse(stateJson.contains("\"value\""))

        val metaJson = json.encodeToString(PlayerMeta(PlayerId("p-meta"), "Tester", 0, Gender.NA))
        assertTrue(metaJson.contains("\"playerId\":\"p-meta\""))
        assertFalse(metaJson.contains("\"value\""))
    }
}
