// GPL-3.0-only: JNI bridge for running the unmodified Stockfish UCI loop in-process.
#include <jni.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <iostream>
#include <mutex>
#include <queue>
#include <streambuf>
#include <string>
#include <thread>
#include <utility>

#include "attacks.h"
#include "misc.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

namespace {
std::queue<std::string> inputQueue;
std::queue<std::string> outputQueue;
std::mutex inputMutex;
std::mutex outputMutex;
std::condition_variable inputAvailable;
std::condition_variable outputAvailable;
std::atomic<bool> engineRunning{false};
std::thread engineThread;
std::streambuf* previousCin = nullptr;
std::streambuf* previousCout = nullptr;

class QueueInputBuffer final : public std::streambuf {
   protected:
    int_type underflow() override {
        if (gptr() != nullptr && gptr() < egptr())
            return traits_type::to_int_type(*gptr());

        std::unique_lock<std::mutex> lock(inputMutex);
        inputAvailable.wait(lock, [] {
            return !inputQueue.empty() || !engineRunning.load();
        });
        if (inputQueue.empty())
            return traits_type::eof();

        currentLine = std::move(inputQueue.front());
        inputQueue.pop();
        currentLine.push_back('\n');
        setg(currentLine.data(), currentLine.data(), currentLine.data() + currentLine.size());
        return traits_type::to_int_type(*gptr());
    }

   private:
    std::string currentLine;
};

class QueueOutputBuffer final : public std::streambuf {
   protected:
    int_type overflow(int_type value) override {
        if (traits_type::eq_int_type(value, traits_type::eof()))
            return traits_type::not_eof(value);

        std::string completed;
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            lineBuffer.push_back(traits_type::to_char_type(value));
            if (traits_type::to_char_type(value) == '\n')
                completed.swap(lineBuffer);
        }
        if (!completed.empty())
            publish(std::move(completed));
        return value;
    }

    int sync() override {
        std::string completed;
        {
            std::lock_guard<std::mutex> lock(bufferMutex);
            completed.swap(lineBuffer);
        }
        if (!completed.empty())
            publish(std::move(completed));
        return 0;
    }

   private:
    void publish(std::string line) {
        {
            std::lock_guard<std::mutex> lock(outputMutex);
            outputQueue.push(std::move(line));
        }
        outputAvailable.notify_one();
    }

    std::mutex bufferMutex;
    std::string lineBuffer;
};

QueueInputBuffer queueInputBuffer;
QueueOutputBuffer queueOutputBuffer;

void runUciLoop() {
    previousCin = std::cin.rdbuf(&queueInputBuffer);
    previousCout = std::cout.rdbuf(&queueOutputBuffer);

    Stockfish::Attacks::init();
    Stockfish::Position::init();

    const char executableName[] = "stockfish";
    char* argv[] = {const_cast<char*>(executableName)};
    Stockfish::CommandLine commandLine(1, argv);
    Stockfish::UCIEngine uci(std::move(commandLine));
    Stockfish::Tune::init(uci.engine_options());
    uci.loop();

    std::cout.flush();
    std::cin.rdbuf(previousCin);
    std::cout.rdbuf(previousCout);
    previousCin = nullptr;
    previousCout = nullptr;
    engineRunning.store(false);
    inputAvailable.notify_all();
}

void clearQueues() {
    std::scoped_lock lock(inputMutex, outputMutex);
    while (!inputQueue.empty()) inputQueue.pop();
    while (!outputQueue.empty()) outputQueue.pop();
}

jboolean sendCommand(JNIEnv* env, jstring value) {
    if (!engineRunning.load() || value == nullptr)
        return JNI_FALSE;

    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr)
        return JNI_FALSE;
    std::string command(chars);
    env->ReleaseStringUTFChars(value, chars);

    {
        std::lock_guard<std::mutex> lock(inputMutex);
        inputQueue.push(std::move(command));
    }
    inputAvailable.notify_one();
    return JNI_TRUE;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_app_aftercheck_analysis_NativeStockfish_nativeStart(JNIEnv*, jclass) {
    if (engineRunning.load())
        return JNI_FALSE;

    if (engineThread.joinable())
        engineThread.join();
    clearQueues();
    engineRunning.store(true);
    engineThread = std::thread(runUciLoop);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_aftercheck_analysis_NativeStockfish_nativeSendCommand(JNIEnv* env, jclass, jstring command) {
    return sendCommand(env, command);
}

extern "C" JNIEXPORT jstring JNICALL
Java_app_aftercheck_analysis_NativeStockfish_nativeReadLine(JNIEnv* env, jclass, jint timeoutMs) {
    std::unique_lock<std::mutex> lock(outputMutex);
    const auto timeout = std::chrono::milliseconds(timeoutMs < 0 ? 0 : timeoutMs);
    if (!outputAvailable.wait_for(lock, timeout, [] { return !outputQueue.empty(); }))
        return nullptr;

    std::string line = std::move(outputQueue.front());
    outputQueue.pop();
    lock.unlock();
    if (!line.empty() && line.back() == '\n')
        line.pop_back();
    return env->NewStringUTF(line.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_aftercheck_analysis_NativeStockfish_nativeIsRunning(JNIEnv*, jclass) {
    return engineRunning.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_app_aftercheck_analysis_NativeStockfish_nativeShutdown(JNIEnv*, jclass) {
    if (engineRunning.load()) {
        {
            std::lock_guard<std::mutex> lock(inputMutex);
            inputQueue.push("quit");
        }
        inputAvailable.notify_one();
    }
    if (engineThread.joinable())
        engineThread.join();

    engineRunning.store(false);
    inputAvailable.notify_all();
    clearQueues();
}
