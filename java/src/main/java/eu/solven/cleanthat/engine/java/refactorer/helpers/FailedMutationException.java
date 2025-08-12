/*
 * Copyright 2025 Benoit Lacelle - SOLVEN
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.solven.cleanthat.engine.java.refactorer.helpers;

/**
 * An exception thrown when a mutation fails.
 *
 * @author Record Robotics
 */
public class FailedMutationException extends RuntimeException {

	static final long serialVersionUID = -1848914671093119416L;

	/**
	 * Constructs an FailedMutationException with the specified detail message. A detail message is a String that
	 * describes this particular exception.
	 *
	 * @param s
	 *            the String that contains a detailed message
	 */
	public FailedMutationException(String s) {
		super(s, null, false, false);
	}
}
