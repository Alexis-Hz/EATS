# Alexis-Hz Cadence quick test

location of the task code
 [eats folder](https://github.com/Alexis-Hz/EATS/tree/main/src/main/java/com/uber/cadence/samples/eats)
- I put all the code in the same sample since that was the established pattern in the repo for samples
- I did not include tests, since i prioritized documentation over it, that is something I would further add if required

My general approach on ramping up to cadence and completing the assignment was the following 

## Cadence ramp up 
> 30m
Video tutorial and documentation ramp up. reading as much of the documentation manually as well as leveraging AI to condense relevant information and guide me to the right documentation parts.
I watched a lot of Cadence videos asynchronously while I was doing other things over the last 2 days, i did not include this time

## Sample code analysis 
> 30m
cloned the sample code repo and analyzed the most relevant components as well as nomenclature and style best practices
leveraged AI to guide me to the key components in each of the samples as well as to quickly find the most relevant samples to leverage

## cadence setup 
> 2hrs
a lot of this was done asynchronously since it required to download a lot of specific versions of Java, gradle and other specific components and libraries.

My key takeaway is that it was very hard to setup cadence using my personal portainer server, there were loads of conflicts with ports and cross container permissions.

It was much easier to just use self host locally to test.

once I had identified the task at hand, requirements I split the task into 4 stages

## Step 1
Execute a simple hello world to test the server and client working
> 20m

## Step 2
Create a Workflow that took an order and printed details 
> 15m

## Step 3
modify the workflow to leverage signals and workflow.sleep to add latency to the workflow as well as change it to be asynchronous
> 40m

My key takeaway here is that it was a little confusing to understand the order of execution of some of the workflow stages, but once I understood it it was fairly easy

## Step 5
Add a child workflow and execute delivery within it using a sleeep command
> 5m

## Step 6
Documentation, cleaning and packaging
> 15m