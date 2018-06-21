package net.corda.training.flow

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.*
import net.corda.core.flows.CollectSignaturesFlow
import net.corda.core.flows.FinalityFlow
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.InitiatedBy
import net.corda.core.flows.InitiatingFlow
import net.corda.core.flows.SignTransactionFlow
import net.corda.core.flows.StartableByRPC
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.contextLogger
import net.corda.training.contract.IOUContract
import net.corda.training.state.IOUState

/**
 * This is the flow which handles issuance of new IOUs on the ledger.
 * Gathering the counterparty's signature is handled by the [CollectSignaturesFlow].
 * Notarisation (if required) and commitment to the ledger is handled by the [FinalityFlow].
 * The flow returns the [SignedTransaction] that was committed to the ledger.
 */
@InitiatingFlow
@StartableByRPC
class IOUIssueFlow(val state: IOUState) : FlowLogic<SignedTransaction>() {
    @Suspendable
    override fun call(): SignedTransaction {
        // Placeholder code to avoid type error when running the tests. Remove before starting the flow task!
        //Create a notary - Single in this scenario
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        //Create transactionbuilder which would need a notary who governs this transaction
        val builder = TransactionBuilder(notary)

        //Create the commands that this transaction needs to do along with the participants that would be signing
        val iouIssueCommands = Command(IOUContract.Commands.Issue(), state.participants.map { it.owningKey })
        builder.addCommand(iouIssueCommands)
        builder.addOutputState(state, IOUContract.IOU_CONTRACT_ID)
        //val ledgerTransaction = builder.toLedgerTransaction(serviceHub)
        builder.verify(serviceHub)
        val partiallySigned = serviceHub.signInitialTransaction(builder)
        val participantsList = state.participants - ourIdentity
        val flowSession = participantsList.map { initiateFlow(it) }.toSet()
        val collectedSignatureFlow = CollectSignaturesFlow(partiallySigned, flowSession)
        val fullySignedTxn = subFlow(collectedSignatureFlow)
        //val identity = ourIdentity.owningKey
        //subflow function commits to ledger
        return subFlow(FinalityFlow(fullySignedTxn))
    }
}

/**
 * This is the flow which signs IOU issuances.
 * The signing is handled by the [SignTransactionFlow].
 */
@InitiatedBy(IOUIssueFlow::class)
class IOUIssueFlowResponder(val flowSession: FlowSession) : FlowLogic<Unit>() {
    @Suspendable
    override fun call() {
        val signedTransactionFlow = object : SignTransactionFlow(flowSession) {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "This must be an IOU transaction" using (output is IOUState)
            }
        }
        subFlow(signedTransactionFlow)
    }
}