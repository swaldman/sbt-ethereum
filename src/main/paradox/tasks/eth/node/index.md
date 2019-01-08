@@@ index

* [ChainId*](chain_id.md)
* [Url*](url.md)

@@@

@@@ div { .no-display #ethNodeToc }

@@toc { depth=4 }

@@@


# ethNode*

_sbt-ethereum_ tasks related to the configuration and state of _Ethereum_ and compatible network nodes, via which _sbt-ethereum_ interacts with a blockchain.
Most basically, each _sbt-ethereum_ session must be associated with an [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) @ref:[Chain ID](chain_id.md),
which specifies which _Ethereum_ compatible chain the session should interact with. There must also be a [node URL](url.md) available for that chain ID. `ethNode*` commands
allow you to configure which Chain ID your session will be associated with, and specify node URLs, scoped to a particular chain ID. You can configure node URLs for all the different chains you interact with, as long as they have unique [EIP-155](https://github.com/ethereum/EIPs/blob/master/EIPS/eip-155.md) Chain IDs.

You can also [check the availability and state](#ethNodeBlockNumberPrint) of the currently configured node.

### ethNodeBlockNumberPrint

### ethNodeChainId*

See @ref:[chain ID commands page](chain_id.md), or choose a command below:

@@@ div { #chainIdList .embedded-toc-list }
&nbsp;
@@@

### ethNodeUrl*

See @ref:[URL commands page](url.md), or choose a command below:

@@@ div { #urlList .embedded-toc-list }
&nbsp;
@@@
