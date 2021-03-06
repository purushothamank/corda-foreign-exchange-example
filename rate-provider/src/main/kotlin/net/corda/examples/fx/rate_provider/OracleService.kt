package net.corda.examples.fx.rate_provider

import net.corda.core.contracts.Command
import net.corda.core.contracts.CommandData
import net.corda.core.crypto.TransactionSignature
import net.corda.core.identity.Party
import net.corda.core.internal.uncheckedCast
import net.corda.core.node.ServiceHub
import net.corda.core.transactions.FilteredTransaction
import net.corda.core.transactions.FilteredTransactionVerificationException
import org.slf4j.Logger
import kotlin.reflect.KClass

// TODO port this into a CorDapp designed to make Oracles' implementation easier
/**
 * Template Oracle Service that facilitates verification logic.
 * It introduces a [sign] function that leverages some abstract functions implementors need to define.
 */
interface OracleService {

    /**
     * Signs a [FilteredTransaction] provided all relevant commands are valid.
     *
     * @param ftx the [FilteredTransaction] to inspect and sign.
     *
     * @return a [TransactionSignature] for the transaction.
     */
    fun sign(ftx: FilteredTransaction): TransactionSignature {

        logger.info("Asked to sign transaction.")
        //TODO here the Oracle should check that either all commands are visible (security > privacy) or that all signers are visible (and that all commands match the signers in order) [it will be available soon]
        // check that the partial Merkle tree is valid.
        ftx.verify()

        // validate the commands.
        if (!ftx.checkWithFun { elem -> check(elem, validatingFunctions.associateBy { it.type }) }) throw FilteredTransactionVerificationException(ftx.id, "Cannot verify Merkle tree for filtered transaction.")

        // sign the Merkle root and return the digital signature.
        return services.createSignature(ftx)
    }

    private fun Command<*>.isSignedByMe() = services.myInfo.legalIdentities.first().owningKey in this.signers

    private fun check(elem: Any, validatingFunctions: Map<Class<out CommandData>, CommandValidation<*>>): Boolean {

        return when (elem) {
        // Oracles only care about commands which have their public key in the signers list.
            elem is Command<*> && elem.isSignedByMe() && elem.value::class.java in validatingFunctions.keys -> {

                val validation: CommandValidation<*> = validatingFunctions[(elem as Command<*>).value::class.java]!!
                val validate: (command: CommandData) -> Boolean = uncheckedCast(validation.validate)
                validate(validation.type.cast(elem.value))
            }
        // Unknown commands are whitelisted
            else -> true
        }
    }

    data class CommandValidation<COMMAND : CommandData>(val type: Class<out COMMAND>, val validate: (command: COMMAND) -> Boolean)

    /**
     * A [Logger] to use in the template.
     */
    val logger: Logger

    /**
     * The [ServiceHub].
     */
    val services: ServiceHub

    /**
     * A [Set] of [CommandValidation] mapping a [CommandData] type to a validating function.
     */
    val validatingFunctions: Set<CommandValidation<*>>

    /**
     * Provides the oracle's identity.
     */
    val ourIdentity: Party
        get() = services.myInfo.legalIdentities[0]
}

inline infix fun <reified COMMAND : CommandData> KClass<COMMAND>.using(noinline validate: (command: COMMAND) -> Boolean) = OracleService.CommandValidation(this.java, validate)