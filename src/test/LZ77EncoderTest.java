package test;

import compression.BitCarry;
import compression.lz77.SuffixArray;

import java.util.ArrayList;
import java.util.function.Consumer;

public class LZ77EncoderTest {
    private static final int REFERENCE_LENGTH_SIZE = 8; //Size in bits to encode length
    private static final int REFERENCE_DISTANCE_SIZE = 16; //Size in bits to encode distance

    //This is because encoded reference uses 3 bytes + 1 bit while raw data uses 3 bytes and 3 bits
    //Yes, we can set it to 3, because 25 < 27, but then we sacrifice length, so in average file it will get worse
    private static final int MIN_DATA_LENGTH = 4;

    //255 + 4 => which is 1 byte used in encoding, remember [0, 1, 2, 3] are never used in length
    //So in the end we will have range [4; 259] - 4 => [0, 255]
    private static final int LOOK_AHEAD_BUFFER_SIZE = (1 << REFERENCE_LENGTH_SIZE) - 1 + MIN_DATA_LENGTH; //256 - 1 + 4 = 259

    //Because of cycling data new possible range is [1, 65536] in case of 111111111 -> 1<8, 1> so we add +1
    private static final int MIN_DATA_DISTANCE = 1;

    private static final int SEARCH_BUFFER_SIZE = (1 << REFERENCE_DISTANCE_SIZE) + MIN_DATA_DISTANCE; //[0; 65535] which is 2 bytes used in encoding

    public static byte[] compress(byte[] data) {
        return compress(data, null);
    }

    public static byte[] compress(byte[] data, Consumer<Float> callback) {
        if (data.length == 0) { return data; }
        SuffixArray suffixArray = new SuffixArray(data, LOOK_AHEAD_BUFFER_SIZE, SEARCH_BUFFER_SIZE, MIN_DATA_LENGTH);
        BitCarry bitCarry = new BitCarry(); //Used to easily add data with ref bit
        int position = 0;

        while (position < data.length - MIN_DATA_LENGTH) {
            int[] reference = suffixArray.nextLongestMatch(position);
            int length = reference[0]; //Length of repeating data

            if (length >= MIN_DATA_LENGTH) {
                //If length more than 3 then encode distance and length and push them to bit carry
                //-1 because (1 << 8): 256, 256 is out of range for byte [0; 255], and -MIN_DATA_LENGTH, because if (length > MIN_DATA_LENGTH)
                int distance = position - reference[1] - MIN_DATA_DISTANCE; //Offset, aka, how much to go back
                //System.out.println("{ " + length + " " + (position - reference[1]) + " " + distance + " }");
                bitCarry.pushBits(0b10, 2); //This determines if next data encoded reference
                bitCarry.pushBits((length - MIN_DATA_LENGTH), REFERENCE_LENGTH_SIZE);
                bitCarry.pushBits(distance, REFERENCE_DISTANCE_SIZE);
                position += length;
            } else {
                //If length less than tree, then push byte as normal
                boolean b1 = isLeadingOne(data[position], 8);
                if (b1) { bitCarry.pushBits(1, 1); }  //This determines if next data is raw data that starts with 1 bit
                bitCarry.pushBits(data[position], 8);
                position++;
            }

            if (callback != null) { callback.accept((float) position/data.length*100); }
        }

        //Write remaining bytes as raw data
        for (int i = position; i < data.length; i++) {
            boolean b1 = isLeadingOne(data[i], 8);
            if (b1) { bitCarry.pushBits(1, 1); }
            bitCarry.pushBits(data[i], 8);
        }

        if (callback != null) { callback.accept(100f); }
        return bitCarry.getBytes(true);
    }

    public static byte[] decompress(byte[] data) {
        return decompress(data, null);
    }

    public static byte[] decompress(byte[] data, Consumer<Float> callback) {
        if (data.length == 0) { return data; }
        BitCarry bitCarry = new BitCarry(data);
        ArrayList<Byte> output = new ArrayList<>();
        int position = 0;

        while (bitCarry.availableBytes() > 0) {
            //D: 01110101 -> 01110101
            if (bitCarry.getBits(1, true, false) == 0) {
                output.add((byte) bitCarry.getBits(8, true, true));
                position += 1;
                continue;
            }

            bitCarry.getBits(1, true, true); //It is definitely 1, we don't need it

            //D: 11110101 -> 1 11110101
            if ((bitCarry.getBits(1, true, false) == 1)) {
                output.add((byte) bitCarry.getBits(8, true, true));
                position += 1;
                continue;
            }

            bitCarry.getBits(1, true, true); //It is definitely 0, we don't need it

            //R: 01110001 -> 1 0 01110001
            //R: 11110001 -> 1 0 11110001
            int length = (int) bitCarry.getBits(REFERENCE_LENGTH_SIZE) + MIN_DATA_LENGTH; //Length is encoded as 1 byte and 1 byte is 8 bits
            int distance = (int) bitCarry.getBits(REFERENCE_DISTANCE_SIZE) + MIN_DATA_DISTANCE; //Distance is encoded as 2 byte and 1 byte is 16 bits

            //Copy bytes in loop from past
            for (int i = 0; i < length; i++) {
                output.add(output.get((position - distance + i)));
            }

            position += length; //Increase position by reference length
            int done = data.length - bitCarry.availableBytes(); //Calculate how many bytes we processed
            if (callback != null) { callback.accept((float) done/data.length*100); }
        }

        if (callback != null) { callback.accept(100f); }
        return BitCarry.copyBytes(output);
    }

    public static boolean isLeadingOne(long data, int size) {
        return (((data >>> (size-1)) & 0x1) == 1);
    }
}
