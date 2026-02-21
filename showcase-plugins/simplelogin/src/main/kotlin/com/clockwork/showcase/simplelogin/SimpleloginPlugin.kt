package com.clockwork.showcase.simplelogin

import com.clockwork.api.*
import java.security.MessageDigest
import java.security.SecureRandom

data class LoginAccount(
    val salt: String,
    val hash: String
)

data class SimpleLoginState(
    val accounts: Map<String, LoginAccount> = emptyMap()
)

class SimpleloginPlugin : GigaPlugin {
    private val stateKey = "simplelogin-state"
    private val accounts = linkedMapOf<String, LoginAccount>()
    private val loggedIn = linkedSetOf<String>()
    private val random = SecureRandom()

    private val delegate = gigaPlugin(id = "showcase-simplelogin", name = "Showcase SimpleLogin", version = "1.0.0") {
        events {
            subscribe(GigaPlayerJoinEvent::class.java) { event ->
                val ctx = pluginContextRef ?: return@subscribe
                val name = event.player.name
                val key = name.lowercase()
                loggedIn.remove(key)
                if (accounts[key] == null) {
                    ctx.host.sendPlayerMessage(name, "[SimpleLogin] First join: /register <password> <password>")
                } else {
                    ctx.host.sendPlayerMessage(name, "[SimpleLogin] Please login: /login <password>")
                }
            }
            subscribe(GigaPlayerLeaveEvent::class.java) { event ->
                loggedIn.remove(event.player.name.lowercase())
            }
        }

        commands {
            spec(
                command = "register",
                argsSchema = listOf(
                    CommandArgSpec("password", CommandArgType.STRING),
                    CommandArgSpec("confirm", CommandArgType.STRING)
                ),
                usage = "register <password> <password>"
            ) { inv ->
                val key = inv.sender.id.lowercase()
                if (accounts[key] != null) return@spec CommandResult.error("Already registered", code = "E_EXISTS")
                val pw = inv.parsedArgs.requiredString("password")
                val confirm = inv.parsedArgs.requiredString("confirm")
                if (pw != confirm) return@spec CommandResult.error("Passwords do not match", code = "E_ARGS")
                if (pw.length < 4) return@spec CommandResult.error("Password too short", code = "E_ARGS")
                val salt = randomSalt()
                accounts[key] = LoginAccount(salt = salt, hash = hashPassword(salt, pw))
                loggedIn.add(key)
                inv.pluginContext.saveState(stateKey, SimpleLoginState(accounts.toMap()))
                CommandResult.ok("Registered and logged in")
            }

            spec(
                command = "login",
                argsSchema = listOf(CommandArgSpec("password", CommandArgType.STRING)),
                usage = "login <password>"
            ) { inv ->
                val key = inv.sender.id.lowercase()
                val account = accounts[key] ?: return@spec CommandResult.error("Not registered", code = "E_NOT_FOUND")
                val pw = inv.parsedArgs.requiredString("password")
                val ok = hashPassword(account.salt, pw) == account.hash
                if (!ok) return@spec CommandResult.error("Wrong password", code = "E_AUTH")
                loggedIn.add(key)
                CommandResult.ok("Login successful")
            }

            spec(command = "logout", usage = "logout") { inv ->
                loggedIn.remove(inv.sender.id.lowercase())
                CommandResult.ok("Logged out")
            }

            spec(
                command = "changepassword",
                argsSchema = listOf(
                    CommandArgSpec("old", CommandArgType.STRING),
                    CommandArgSpec("new", CommandArgType.STRING),
                    CommandArgSpec("confirm", CommandArgType.STRING)
                ),
                usage = "changepassword <old> <new> <new>"
            ) { inv ->
                val key = inv.sender.id.lowercase()
                val account = accounts[key] ?: return@spec CommandResult.error("Not registered", code = "E_NOT_FOUND")
                if (key !in loggedIn) return@spec CommandResult.error("Login first", code = "E_AUTH")
                val oldPw = inv.parsedArgs.requiredString("old")
                if (hashPassword(account.salt, oldPw) != account.hash) {
                    return@spec CommandResult.error("Old password invalid", code = "E_AUTH")
                }
                val newPw = inv.parsedArgs.requiredString("new")
                val confirm = inv.parsedArgs.requiredString("confirm")
                if (newPw != confirm) return@spec CommandResult.error("Passwords do not match", code = "E_ARGS")
                val newSalt = randomSalt()
                accounts[key] = LoginAccount(salt = newSalt, hash = hashPassword(newSalt, newPw))
                inv.pluginContext.saveState(stateKey, SimpleLoginState(accounts.toMap()))
                CommandResult.ok("Password changed")
            }

            spec(
                command = "login-status",
                argsSchema = listOf(CommandArgSpec("player", CommandArgType.STRING, required = false)),
                usage = "login-status [player]"
            ) { inv ->
                val player = (inv.parsedArgs.string("player") ?: inv.sender.id).trim().lowercase()
                val registered = player in accounts
                val session = player in loggedIn
                CommandResult.ok("$player registered=$registered loggedIn=$session")
            }
        }
    }

    @Volatile
    private var pluginContextRef: PluginContext? = null

    override fun onEnable(ctx: PluginContext) {
        pluginContextRef = ctx
        val state = ctx.loadOrDefault(stateKey) { SimpleLoginState() }
        accounts.clear()
        accounts.putAll(state.accounts)
        loggedIn.clear()
        delegate.onEnable(ctx)
    }

    override fun onDisable(ctx: PluginContext) {
        ctx.saveState(stateKey, SimpleLoginState(accounts.toMap()))
        loggedIn.clear()
        pluginContextRef = null
        delegate.onDisable(ctx)
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashPassword(salt: String, password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val input = "$salt::$password".toByteArray(Charsets.UTF_8)
        return md.digest(input).joinToString("") { "%02x".format(it) }
    }
}
