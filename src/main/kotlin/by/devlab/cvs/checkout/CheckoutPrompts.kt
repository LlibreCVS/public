package by.devlab.cvs.checkout

/**
 * The question a checkout with problems asks the user (design decision 12): the production implementation shows a
 * dialog, tests answer it. Called on the UI thread.
 */
fun interface CheckoutPrompts {

    /** Whether to create the project from what arrived of [module], although the checkout had [problems]. */
    fun askCreateProject(module: String, problems: CheckoutProblems): Boolean

    companion object {
        /** Shows [CheckoutProblemsDialog]. */
        val UI: CheckoutPrompts = CheckoutPrompts { module, problems -> CheckoutProblemsDialog(module, problems).showAndGet() }
    }
}
