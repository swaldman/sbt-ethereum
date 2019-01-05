# Using a Smart Contract (II)

### Calling methods that change the data on the blockchain

In @ref:[Using a Smart Contract (I)](using-a-smart-contract-i.md#calling-methods-that-change-the-data-on-the-blockchain),
we saw that while we can _access_ data from an _Ethereum_ smart contract for free, if we want to call methods that _change_ the state of the smart contract, we must pay to submit a transaction.

You may recall we tried the command `ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please."`. It failed, because we didn't have enough ETH to pay for the transaction.
Now our default address is funded. So let's try again!
```
sbt:eth-command-line> ethTransactionInvoke fortune addFortune "Make up a better fortune than this, please."
[info] Unlocking address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (on chain with ID 1, aliases ['default-sender'])
Enter passphrase or hex private key for address '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2': ***************
[info] V3 wallet(s) found for '0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2' (aliases ['default-sender'])

==> T R A N S A C T I O N   S U B M I S S I O N   R E Q U E S T
==>
==> The transaction would be a message with...
==>   To:    0x82ea8ab1e836272322f376a5f71d5a34a71688f1 (with aliases ['fortune'] on chain with ID 1)
==>   From:  0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2 (with aliases ['default-sender'] on chain with ID 1)
==>   Data:  0x4cf373e60000000000000000000000000000000000000000000000000000000000000020000000000000000000000000000000000000000000000000000000000000002b4d616b6520757020612062657474657220666f7274756e65207468616e20746869732c20706c656173652e000000000000000000000000000000000000000000
==>   Value: 0 Ether
==>
==> According to the ABI currently associated with the 'to' address, this message would amount to the following method call...
==>   Function called: addFortune(string)
==>     Arg 1 [name=fortune, type=string]: "Make up a better fortune than this, please."
==>
==> The nonce of the transaction would be 3.
==>
==> $$$ The transaction you have requested could use up to 112773 units of gas.
==> $$$ You would pay 2 gwei for each unit of gas, for a maximum cost of 0.000225546 ether.
==> $$$ This is worth 0.035844898050 USD (according to Coinbase at 10:06 PM).

Would you like to submit this transaction? [y/n] y
A transaction with hash '0xe062f0c49cbb2876b6e8a22beb1c1b57e1fd3b612ed572c0ccb9be6c02b4ca3f' will be submitted. Please wait.
[info] Called function 'addFortune', with args '"Make up a better fortune than this, please."', sending 0 wei to address '0x82ea8ab1e836272322f376a5f71d5a34a71688f1' in transaction '0xe062f0c49cbb2876b6e8a22beb1c1b57e1fd3b612ed572c0ccb9be6c02b4ca3f'.
[info] Waiting for the transaction to be mined (will wait up to 5 minutes).
[info] Transaction Receipt:
[info]        Transaction Hash:    0xe062f0c49cbb2876b6e8a22beb1c1b57e1fd3b612ed572c0ccb9be6c02b4ca3f
[info]        Transaction Index:   134
[info]        Transaction Status:  SUCCEEDED
[info]        Block Hash:          0x7f5883c8b3beb80d2e36c794685bd0a1d6651624d7ae6dcd485a35dc4c05f801
[info]        Block Number:        7012937
[info]        From:                0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2
[info]        To:                  0x82ea8ab1e836272322f376a5f71d5a34a71688f1
[info]        Cumulative Gas Used: 7635630
[info]        Gas Used:            93978
[info]        Contract Address:    None
[info]        Logs:                EthLogEntry(EthAddress(ByteSeqExact20(0x82ea8ab1e836272322f376a5f71d5a34a71688f1)),Vector(ByteSeqExact32(0xaf1abf70f2d9f0d04e56242efc047451c912ad8f53a3b6d4391246d92ce889ff)),ImmutableArraySeq.Byte(0x0000000000000000000000001144f4f7aad0c463c667e0f8d73fc13f1e7e86a20000000000000000000000000000000000000000000000000000000000000040000000000000000000000000000000000000000000000000000000000000002b4d616b6520757020612062657474657220666f7274756e65207468616e20746869732c20706c656173652e000000000000000000000000000000000000000000))
[info]        Events:              Named(List(Value(Parameter(author,address,false),EthAddress(ByteSeqExact20(0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2)),0x1144f4f7aad0c463c667e0f8d73fc13f1e7e86a2), Value(Parameter(fortune,string,false),Vector(77, 97, 107, 101, 32, 117, 112, 32, 97, 32, 98, 101, 116, 116, 101, 114, 32, 102, 111, 114, 116, 117, 110, 101, 32, 116, 104, 97, 110, 32, 116, 104, 105, 115, 44, 32, 112, 108, 101, 97, 115, 101, 46),"Make up a better fortune than this, please.")),EthLogEntry(EthAddress(ByteSeqExact20(0x82ea8ab1e836272322f376a5f71d5a34a71688f1)),Vector(ByteSeqExact32(0xaf1abf70f2d9f0d04e56242efc047451c912ad8f53a3b6d4391246d92ce889ff)),ImmutableArraySeq.Byte(0x0000000000000000000000001144f4f7aad0c463c667e0f8d73fc13f1e7e86a20000000000000000000000000000000000000000000000000000000000000040000000000000000000000000000000000000000000000000000000000000002b4d616b6520757020612062657474657220666f7274756e65207468616e20746869732c20706c656173652e000000000000000000000000000000000000000000)),Event(FortuneAdded,List(Parameter(author,address,false), Parameter(fortune,string,false)),false))
[success] Total time: 69 s, completed Jan 4, 2019 10:07:20 PM
```
It seems to have worked!

Let's verify that. Remember `ethTransactionView`, which lets us access the state of a smart contract without modifying it, very easily?
```
sbt:eth-command-line> ethTransactionView fortune 
countFortunes   drawFortune     fortunes        
sbt:eth-command-line> ethTransactionView fortune countFortunes
[info] The function 'countFortunes' yields 1 result.
[info]  + Result 1 of type 'uint256', named 'count', is 11
[success] Total time: 1 s, completed Jan 4, 2019 10:26:48 PM
sbt:eth-command-line> ethTransactionView fortune fortunes 
<mapping key, of type uint256>   ​                                 
sbt:eth-command-line> ethTransactionView fortune fortunes 10
[info] The function 'fortunes' yields 1 result.
[info]  + Result 1 of type 'string' is "Make up a better fortune than this, please."
[success] Total time: 0 s, completed Jan 4, 2019 10:27:07 PM
```

We check how many fortunes the smart contract currently knows with `ethTransactionView fortune countFortunes`.
At the time of this writing, there are 11, which would have been indexed from 0 to 10. So we ask for the last, tenth one
-- `ethTransactionView fortune fortunes 10`, and sure enough it is the fortune we have just submitted.

That fortune was really boring though. If you are following along at home, please come up with something better.

Just for the heck of it, let's draw a (quasi)random fortune to learn what will soon befall us.
```
sbt:eth-command-line> ethTransactionView fortune drawFortune
[info] The function 'drawFortune' yields 1 result.
[info]  + Result 1 of type 'string', named 'fortune', is "Your identity is unique!"
[success] Total time: 1 s, completed Jan 4, 2019 10:31:40 PM
```

I feel so very special.
