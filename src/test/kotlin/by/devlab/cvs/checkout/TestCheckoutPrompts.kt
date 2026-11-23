package by.devlab.cvs.checkout

/** [CheckoutPrompts] for tests: records every question and answers it with [createProject]. */
class TestCheckoutPrompts(var createProject: Boolean = true) : CheckoutPrompts {

    /** One question: the module it was asked for and the problems it listed. */
    data class Question(val module: String, val problems: CheckoutProblems)

    val asked = mutableListOf<Question>()

    override fun askCreateProject(module: String, problems: CheckoutProblems): Boolean {
        asked += Question(module, problems)
        return createProject
    }
}
