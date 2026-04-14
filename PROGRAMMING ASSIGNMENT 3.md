# Using LLMs to find answers with additional context (extracted text from Programming assignment 2)

```
question = query -> [PA 2 RETRIEVAL (vector db)] -> text segments (plain text) -> [LLM]
               \------------------------------------------------------------------/
```

We give additional context to the LLM from our database of retrieved data from GITHUB repositories, which will greatly improve the responses of it.

When using LLMs we notice that answers may not be relevant to us. This is because the model did not have enough context to know what to answer.

### We will run the Large Language Models locally.

There are several advantages:

1. If you have sensitive data, you don't want to send it to the internet
2. Eventhough we run a local LLM, it can generate really good responses, if you provide it with enough good data!
3. Selecting an appropriate LLM (doesn't have lots of parameters, is specific for the language used - Gams for Slovenia language, or multilingual model) impacts the reponses.

We will use the LLama model, that we will run locally.

### Local Installation of Ollama

Ollama enables us to run Large Language Models locally, even though we don't have a GPU. It will abstract away the hardware configuration and other configuration that we would otherwise need to do, to be able to run it locally.

We will use the smallest Llama, with 3.5B parameters (LLama3.2?), which uses around 1.3GB of RAM.

#### Configuration is really small:

```
import dspy
lm = dspy.LM('ollama_chat/llama3.2:1b', api_base='http://localhost:11434', api_key='')
dspy.configure(lm=lm)
```

This is all we have to do to run it.

Now for the main part. If the LLM doesn't have enough information to correctly answer, as for example "Who is the profesor for course Web Information and Extraction and Retrieval?" it responds that it doesn't have enough specific information to asnwer correctly. But by providing more context information, for example extracting the plain text of the FRI WIER webpage, the LLM would have enough information to respond correctly and more insigtfully.

#### This is done in the section "Adding context to the Query" - the "Without context" and "With retrieved context"

In this section it shows a simple way of adding context to the prompt.

### Using DsPY to generate a prompt

we have a class which has the same input and context, as before, and output is our answer. But the implementation is more abstract, as we abstracted it into a class instead of plain flat code.

### BUT the context is not provided by the PA2 RETRIEVAL COMPONENT, THAT WE NEED TO DO FOR THE SECOND PROGRAMMING ASSIGNMENT. In this part, we will query the results from the vector db, the best result for the query or the best 5 results for the query and feed this as the context.

## This last programming assignment 3 will not have much programming. The main dificulties will be connecting the PA2 RETRIEVAL system with the model, and dealing with  bad information retrieval from the database, which will make the responses worse than what they already were. The responses may be slow to generate if we will give him too much context...

To use Slovene language we can use GaMS model, as provided in the vaje5-RAG_simple_demo.ipynb, which is a 9B parameter model. But it is slow to generate the responses. Or spain developed Salamandra model, which also works well on all European languages, aka also for slovene language.

Our task would be to observe the quality of the answers with different queries. If the LLM provides good answeres to our questions, if it doesn't have enough relevant information to answer our question correctly (than we are retrieving information wrongly from the database).